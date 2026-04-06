package com.example.agent.sessions;

import com.anthropic.models.messages.*;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.Console;
import com.example.agent.util.PathSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * S12：Git Worktree 隔离 —— 自包含实现（不依赖共享 TaskManager / WorktreeManager）。
 * <p>
 * 任务是控制平面（做什么），Worktree 是执行平面（在哪做）。
 * 每个 Worktree 是一个独立的 git 工作副本，可绑定到一个任务。
 * <pre>
 * 控制平面 (Tasks)          执行平面 (Worktrees)
 * ┌─────────────┐          ┌──────────────────┐
 * │ Task #1     │ ────────→│ wt/auth-refactor │
 * │ Task #2     │ ────────→│ wt/fix-tests     │
 * │ Task #3     │          │   (unclaimed)     │
 * └─────────────┘          └──────────────────┘
 * </pre>
 * <p>
 * 生命周期：active → kept / removed
 * <p>
 * 关键洞察："按目录隔离，按任务 ID 协调。文件系统即隔离边界。"
 * <p>
 * REPL 命令：/tasks, /worktrees, /wt, /events
 * <p>
 * 对应 Python 原版：s12_worktree_task_isolation.py
 */
public class S12WorktreeIsolation {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Worktree 名称正则：1-40 个字母/数字/点/下划线/横线 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    // ---- Paths ----
    private static Path repoRoot;
    private static Path workDir;
    private static PathSandbox sandbox;
    private static Path tasksDir;
    private static Path worktreeDir;
    private static Path indexPath;
    private static Path eventsPath;

    // ---- State ----
    private static int nextTaskId;
    private static boolean gitAvailable;

    public static void main(String[] args) {
        repoRoot = detectRepoRoot();
        workDir = Path.of(System.getProperty("user.dir"));
        sandbox = new PathSandbox(workDir);
        tasksDir = repoRoot.resolve(".tasks");
        worktreeDir = repoRoot.resolve(".worktrees");
        indexPath = worktreeDir.resolve("index.json");
        eventsPath = worktreeDir.resolve("events.jsonl");

        // 初始化目录
        try { Files.createDirectories(tasksDir); } catch (IOException ignored) {}
        try { Files.createDirectories(worktreeDir); } catch (IOException ignored) {}
        try {
            if (!Files.exists(indexPath)) {
                Files.writeString(indexPath, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(Map.of("worktrees", new ArrayList<>())));
            }
            if (!Files.exists(eventsPath)) {
                Files.writeString(eventsPath, "");
            }
        } catch (IOException ignored) {}

        gitAvailable = checkGitRepo();
        nextTaskId = maxTaskId() + 1;

        if (!gitAvailable) {
            System.err.println("警告: 当前目录不在 git 仓库中，worktree 功能不可用。");
        }

        String systemPrompt = "You are a coding agent at " + workDir
                + " (repo root: " + repoRoot + "). "
                + "Use task + worktree tools for multi-task work. "
                + "For parallel or risky changes: create tasks, allocate worktree lanes, "
                + "run commands in those lanes, then choose keep/remove for closeout. "
                + "Use worktree_events when you need lifecycle visibility.";

        // ---- 工具定义（16 个） ----
        List<Tool> tools = List.of(
                AgentLoop.defineTool("bash", "Run a shell command in the current workspace (blocking).",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
                AgentLoop.defineTool("read_file", "Read file contents.",
                        Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer")),
                        List.of("path")),
                AgentLoop.defineTool("write_file", "Write content to file.",
                        Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")),
                        List.of("path", "content")),
                AgentLoop.defineTool("edit_file", "Replace exact text in file.",
                        Map.of("path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),
                // ---- 任务工具 ----
                AgentLoop.defineTool("task_create", "Create a new task on the shared task board.",
                        Map.of("subject", Map.of("type", "string"),
                                "description", Map.of("type", "string")),
                        List.of("subject")),
                AgentLoop.defineTool("task_list", "List all tasks with status, owner, and worktree binding.",
                        Map.of(), null),
                AgentLoop.defineTool("task_get", "Get task details by ID.",
                        Map.of("task_id", Map.of("type", "integer")),
                        List.of("task_id")),
                AgentLoop.defineTool("task_update", "Update task status or owner.",
                        Map.of("task_id", Map.of("type", "integer"),
                                "status", Map.of("type", "string",
                                        "enum", List.of("pending", "in_progress", "completed")),
                                "owner", Map.of("type", "string")),
                        List.of("task_id")),
                AgentLoop.defineTool("task_bind_worktree", "Bind a task to a worktree name.",
                        Map.of("task_id", Map.of("type", "integer"),
                                "worktree", Map.of("type", "string"),
                                "owner", Map.of("type", "string")),
                        List.of("task_id", "worktree")),
                // ---- Worktree 工具 ----
                AgentLoop.defineTool("worktree_create",
                        "Create a git worktree and optionally bind it to a task.",
                        Map.of("name", Map.of("type", "string"),
                                "task_id", Map.of("type", "integer"),
                                "base_ref", Map.of("type", "string")),
                        List.of("name")),
                AgentLoop.defineTool("worktree_list",
                        "List worktrees tracked in .worktrees/index.json.",
                        Map.of(), null),
                AgentLoop.defineTool("worktree_status",
                        "Show git status for one worktree.",
                        Map.of("name", Map.of("type", "string")),
                        List.of("name")),
                AgentLoop.defineTool("worktree_run",
                        "Run a shell command in a named worktree directory.",
                        Map.of("name", Map.of("type", "string"),
                                "command", Map.of("type", "string")),
                        List.of("name", "command")),
                AgentLoop.defineTool("worktree_remove",
                        "Remove a worktree and optionally mark its bound task completed.",
                        Map.of("name", Map.of("type", "string"),
                                "force", Map.of("type", "boolean"),
                                "complete_task", Map.of("type", "boolean")),
                        List.of("name")),
                AgentLoop.defineTool("worktree_keep",
                        "Mark a worktree as kept in lifecycle state without removing it.",
                        Map.of("name", Map.of("type", "string")),
                        List.of("name")),
                AgentLoop.defineTool("worktree_events",
                        "List recent worktree/task lifecycle events from .worktrees/events.jsonl.",
                        Map.of("limit", Map.of("type", "integer")), null)
        );

        // ---- 工具分发器 ----
        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
        // 任务工具
        dispatcher.register("task_create", input ->
                createTask((String) input.get("subject"),
                        (String) input.getOrDefault("description", "")));
        dispatcher.register("task_list", input -> listTasks());
        dispatcher.register("task_get", input ->
                getTask(((Number) input.get("task_id")).intValue()));
        dispatcher.register("task_update", input ->
                updateTask(((Number) input.get("task_id")).intValue(),
                        (String) input.get("status"),
                        (String) input.get("owner")));
        dispatcher.register("task_bind_worktree", input ->
                bindWorktree(((Number) input.get("task_id")).intValue(),
                        (String) input.get("worktree"),
                        (String) input.getOrDefault("owner", "")));
        // Worktree 工具
        dispatcher.register("worktree_create", input ->
                createWorktree((String) input.get("name"),
                        input.get("task_id") instanceof Number n ? n.intValue() : null,
                        (String) input.get("base_ref")));
        dispatcher.register("worktree_list", input -> listWorktrees());
        dispatcher.register("worktree_status", input ->
                worktreeStatus((String) input.get("name")));
        dispatcher.register("worktree_run", input ->
                worktreeRun((String) input.get("name"), (String) input.get("command")));
        dispatcher.register("worktree_remove", input ->
                removeWorktree((String) input.get("name"),
                        Boolean.TRUE.equals(input.get("force")),
                        Boolean.TRUE.equals(input.get("complete_task"))));
        dispatcher.register("worktree_keep", input ->
                keepWorktree((String) input.get("name")));
        dispatcher.register("worktree_events", input ->
                recentEvents(input.get("limit") instanceof Number n ? n.intValue() : 20));

        // ---- REPL ----
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        System.out.println("Repo root for s12: " + repoRoot);
        if (!gitAvailable) {
            System.out.println("Note: Not in a git repo. worktree_* tools will return errors.");
        }

        while (true) {
            System.out.print(Console.cyan("s12 >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            if ("/tasks".equals(query)) {
                System.out.println(listTasks());
                continue;
            }
            if ("/worktrees".equals(query) || "/wt".equals(query)) {
                System.out.println(listWorktrees());
                continue;
            }
            if ("/events".equals(query)) {
                System.out.println(recentEvents(10));
                continue;
            }

            paramsBuilder.addUserMessage(query);
            try {
                agent.agentLoop(paramsBuilder);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
    }

    // ==================== Git 检测 ====================

    private static Path detectRepoRoot() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--show-toplevel");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && !output.isEmpty()) {
                return Path.of(output);
            }
        } catch (Exception ignored) {}
        System.err.println("警告: 无法检测 git 仓库根目录，使用当前工作目录。");
        return Path.of(System.getProperty("user.dir"));
    }

    private static boolean checkGitRepo() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree");
            pb.directory(repoRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String runGit(String... args) {
        if (!gitAvailable) throw new RuntimeException("Not in a git repository.");
        try {
            var cmd = new ArrayList<String>();
            cmd.add("git");
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(repoRoot.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new RuntimeException("git command timeout");
            }
            if (p.exitValue() != 0) throw new RuntimeException(output.isEmpty() ? "git command failed" : output);
            return output.isEmpty() ? "(no output)" : output;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    // ==================== EventBus ====================

    private static void emitEvent(String event, Map<String, Object> task,
                                   Map<String, Object> worktree, String error) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("event", event);
        payload.put("ts", System.currentTimeMillis() / 1000.0);
        payload.put("task", task != null ? task : Map.of());
        payload.put("worktree", worktree != null ? worktree : Map.of());
        if (error != null) payload.put("error", error);
        try {
            Files.writeString(eventsPath, MAPPER.writeValueAsString(payload) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    private static String recentEvents(int limit) {
        try {
            var lines = Files.readAllLines(eventsPath);
            int n = Math.max(1, Math.min(limit, 200));
            var recent = lines.subList(Math.max(0, lines.size() - n), lines.size());
            var items = new ArrayList<Object>();
            for (String line : recent) {
                if (!line.isBlank()) {
                    try { items.add(MAPPER.readValue(line, Map.class)); }
                    catch (Exception ignored) { items.add(Map.of("event", "parse_error", "raw", line)); }
                }
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(items);
        } catch (IOException e) {
            return "[]";
        }
    }

    // ==================== Task 管理（含 worktree 绑定） ====================

    private static int maxTaskId() {
        try (var stream = Files.list(tasksDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .mapToInt(p -> {
                        String name = p.getFileName().toString();
                        return Integer.parseInt(name.substring(5, name.length() - 5));
                    })
                    .max()
                    .orElse(0);
        } catch (IOException e) {
            return 0;
        }
    }

    private static Path taskPath(int taskId) {
        return tasksDir.resolve("task_" + taskId + ".json");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadTask(int taskId) {
        Path path = taskPath(taskId);
        if (!Files.exists(path)) throw new IllegalArgumentException("Task " + taskId + " not found");
        try {
            return MAPPER.readValue(Files.readString(path), Map.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load task: " + e.getMessage());
        }
    }

    private static void saveTask(Map<String, Object> task) {
        try {
            int id = ((Number) task.get("id")).intValue();
            task.put("updated_at", System.currentTimeMillis() / 1000.0);
            Files.writeString(taskPath(id), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task));
        } catch (IOException e) {
            throw new RuntimeException("Failed to save task: " + e.getMessage());
        }
    }

    private static synchronized String createTask(String subject, String description) {
        var task = new LinkedHashMap<String, Object>();
        task.put("id", nextTaskId);
        task.put("subject", subject);
        task.put("description", description != null ? description : "");
        task.put("status", "pending");
        task.put("owner", "");
        task.put("worktree", "");
        task.put("created_at", System.currentTimeMillis() / 1000.0);
        task.put("updated_at", System.currentTimeMillis() / 1000.0);
        saveTask(task);
        nextTaskId++;
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (IOException e) {
            return task.toString();
        }
    }

    private static String getTask(int taskId) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(loadTask(taskId));
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private static synchronized String updateTask(int taskId, String status, String owner) {
        var task = loadTask(taskId);
        if (status != null) {
            if (!List.of("pending", "in_progress", "completed").contains(status)) {
                return "Error: Invalid status: " + status;
            }
            task.put("status", status);
        }
        if (owner != null) {
            task.put("owner", owner);
        }
        saveTask(task);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (IOException e) {
            return task.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static synchronized String bindWorktree(int taskId, String worktree, String owner) {
        var task = loadTask(taskId);
        task.put("worktree", worktree);
        if (owner != null && !owner.isEmpty()) {
            task.put("owner", owner);
        }
        if ("pending".equals(task.get("status"))) {
            task.put("status", "in_progress");
        }
        saveTask(task);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (IOException e) {
            return task.toString();
        }
    }

    @SuppressWarnings("unchecked")
    private static synchronized String unbindWorktree(int taskId) {
        var task = loadTask(taskId);
        task.put("worktree", "");
        saveTask(task);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (IOException e) {
            return task.toString();
        }
    }

    private static String listTasks() {
        try (var stream = Files.list(tasksDir)) {
            var tasks = stream
                    .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .sorted()
                    .map(p -> {
                        try {
                            return (Map<String, Object>) MAPPER.readValue(Files.readString(p), Map.class);
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();

            if (tasks.isEmpty()) return "No tasks.";

            var lines = new ArrayList<String>();
            for (var t : tasks) {
                String status = (String) t.getOrDefault("status", "?");
                String marker = switch (status) {
                    case "pending" -> "[ ]";
                    case "in_progress" -> "[>]";
                    case "completed" -> "[x]";
                    default -> "[?]";
                };
                String owner = t.get("owner") != null && !t.get("owner").toString().isEmpty()
                        ? " owner=" + t.get("owner") : "";
                String wt = t.get("worktree") != null && !t.get("worktree").toString().isEmpty()
                        ? " wt=" + t.get("worktree") : "";
                lines.add(marker + " #" + t.get("id") + ": " + t.get("subject") + owner + wt);
            }
            return String.join("\n", lines);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================== Worktree 管理 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadIndex() throws IOException {
        return MAPPER.readValue(Files.readString(indexPath), Map.class);
    }

    private static void saveIndex(Map<String, Object> index) throws IOException {
        Files.writeString(indexPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(index));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findWorktree(String name) throws IOException {
        var index = loadIndex();
        var wts = (List<Map<String, Object>>) index.get("worktrees");
        return wts.stream().filter(w -> name.equals(w.get("name"))).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static String createWorktree(String name, Integer taskId, String baseRef) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            return "Error: Invalid worktree name. Use 1-40 chars: letters, numbers, ., _, -";
        }

        try {
            if (findWorktree(name) != null) {
                return "Error: Worktree '" + name + "' already exists in index";
            }
            if (taskId != null && !Files.exists(taskPath(taskId))) {
                return "Error: Task " + taskId + " not found";
            }

            Path path = worktreeDir.resolve(name);
            String branch = "wt/" + name;
            String ref = baseRef != null ? baseRef : "HEAD";

            emitEvent("worktree.create.before",
                    taskId != null ? Map.of("id", taskId) : null,
                    Map.of("name", name, "base_ref", ref), null);

            runGit("worktree", "add", "-b", branch, path.toString(), ref);

            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", name);
            entry.put("path", path.toString());
            entry.put("branch", branch);
            entry.put("task_id", taskId);
            entry.put("status", "active");
            entry.put("created_at", System.currentTimeMillis() / 1000.0);

            var index = loadIndex();
            ((List<Map<String, Object>>) index.get("worktrees")).add(entry);
            saveIndex(index);

            // 绑定 task → worktree
            if (taskId != null) {
                bindWorktree(taskId, name, "");
            }

            emitEvent("worktree.create.after",
                    taskId != null ? Map.of("id", taskId) : null,
                    Map.of("name", name, "path", path.toString(), "branch", branch, "status", "active"), null);

            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entry);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            emitEvent("worktree.create.failed", null, Map.of("name", name), e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private static String listWorktrees() {
        try {
            var index = loadIndex();
            var wts = (List<Map<String, Object>>) index.get("worktrees");
            if (wts.isEmpty()) return "No worktrees in index.";
            var lines = new ArrayList<String>();
            for (var wt : wts) {
                String suffix = wt.get("task_id") != null ? " task=" + wt.get("task_id") : "";
                lines.add("[" + wt.getOrDefault("status", "unknown") + "] " + wt.get("name")
                        + " -> " + wt.get("path") + " (" + wt.getOrDefault("branch", "-") + ")" + suffix);
            }
            return String.join("\n", lines);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String worktreeStatus(String name) {
        try {
            var wt = findWorktree(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";
            Path path = Path.of((String) wt.get("path"));
            if (!Files.exists(path)) return "Error: Worktree path missing: " + path;

            ProcessBuilder pb = new ProcessBuilder("git", "status", "--short", "--branch");
            pb.directory(path.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(60, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "Error: Timeout (60s)";
            }
            return output.isEmpty() ? "Clean worktree" : output;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static String worktreeRun(String name, String command) {
        try {
            var wt = findWorktree(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";
            Path path = Path.of((String) wt.get("path"));
            if (!Files.exists(path)) return "Error: Worktree path missing: " + path;

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(path.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(300, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "Error: Timeout (300s)";
            }
            return output.isEmpty() ? "(no output)" : (output.length() > 50000 ? output.substring(0, 50000) : output);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private static String removeWorktree(String name, boolean force, boolean completeTask) {
        try {
            var wt = findWorktree(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";

            emitEvent("worktree.remove.before",
                    wt.get("task_id") != null ? Map.of("id", wt.get("task_id")) : null,
                    Map.of("name", name, "path", wt.getOrDefault("path", "")), null);

            var args = new ArrayList<>(List.of("worktree", "remove"));
            if (force) args.add("--force");
            args.add((String) wt.get("path"));
            runGit(args.toArray(new String[0]));

            // complete_task: 标记绑定的 task 为 completed 并解绑
            if (completeTask && wt.get("task_id") != null) {
                int taskId = ((Number) wt.get("task_id")).intValue();
                try {
                    var before = loadTask(taskId);
                    updateTask(taskId, "completed", null);
                    unbindWorktree(taskId);
                    emitEvent("task.completed",
                            Map.of("id", taskId, "subject", before.getOrDefault("subject", ""),
                                    "status", "completed"),
                            Map.of("name", name), null);
                } catch (Exception ignored) {}
            }

            // 更新 index
            var index = loadIndex();
            for (var item : (List<Map<String, Object>>) index.get("worktrees")) {
                if (name.equals(item.get("name"))) {
                    item.put("status", "removed");
                    item.put("removed_at", System.currentTimeMillis() / 1000.0);
                }
            }
            saveIndex(index);

            emitEvent("worktree.remove.after",
                    wt.get("task_id") != null ? Map.of("id", wt.get("task_id")) : null,
                    Map.of("name", name, "status", "removed"), null);

            return "Removed worktree '" + name + "'";
        } catch (Exception e) {
            emitEvent("worktree.remove.failed", null, Map.of("name", name), e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private static String keepWorktree(String name) {
        try {
            var wt = findWorktree(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";

            var index = loadIndex();
            Map<String, Object> kept = null;
            for (var item : (List<Map<String, Object>>) index.get("worktrees")) {
                if (name.equals(item.get("name"))) {
                    item.put("status", "kept");
                    item.put("kept_at", System.currentTimeMillis() / 1000.0);
                    kept = item;
                }
            }
            saveIndex(index);

            emitEvent("worktree.keep",
                    wt.get("task_id") != null ? Map.of("id", wt.get("task_id")) : null,
                    Map.of("name", name, "path", wt.get("path"), "status", "kept"), null);

            return kept != null
                    ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(kept)
                    : "Error: Unknown worktree '" + name + "'";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
