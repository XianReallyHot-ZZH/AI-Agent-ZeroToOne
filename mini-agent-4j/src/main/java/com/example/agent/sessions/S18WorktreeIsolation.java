package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * S18：Git Worktree 隔离 —— 任务与执行平面的分离（自包含实现）。
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
 * 核心洞察："按目录隔离，按任务 ID 协调。文件系统即隔离边界。"
 * <p>
 * 整个文件完全自包含。对应 Python 原版：s18_worktree_task_isolation.py
 */
public class S18WorktreeIsolation {

    // ==================== 常量定义 ====================

    private static final Path WORKDIR = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    private static final long MAX_TOKENS = 8000;
    private static final int BASH_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT = 50000;
    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_CYAN = "\033[36m";
    private static final String ANSI_DIM = "\033[2m";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    // ---- 路径 ----
    private static Path repoRoot;
    private static final Path tasksDir = WORKDIR.resolve(".tasks");
    private static Path worktreeDir;
    private static Path indexPath;
    private static Path eventsPath;

    // ---- 状态 ----
    private static int nextTaskId;
    private static boolean gitAvailable;

    // ==================== 主入口 ====================

    public static void main(String[] args) {
        repoRoot = detectRepoRoot();
        worktreeDir = repoRoot.resolve(".worktrees");
        indexPath = worktreeDir.resolve("index.json");
        eventsPath = worktreeDir.resolve("events.jsonl");

        try { Files.createDirectories(tasksDir); } catch (IOException ignored) {}
        try { Files.createDirectories(worktreeDir); } catch (IOException ignored) {}
        try {
            if (!Files.exists(indexPath))
                Files.writeString(indexPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("worktrees", new ArrayList<>())));
            if (!Files.exists(eventsPath)) Files.writeString(eventsPath, "");
        } catch (IOException ignored) {}

        gitAvailable = checkGitRepo();
        nextTaskId = maxTaskId() + 1;
        if (!gitAvailable) System.err.println("警告: 当前目录不在 git 仓库中，worktree 功能不可用。");

        Dotenv dotenv = Dotenv.configure().directory(WORKDIR.toString()).ignoreIfMissing().load();
        String baseUrl = dotenv.get("ANTHROPIC_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) System.clearProperty("ANTHROPIC_AUTH_TOKEN");
        String apiKey = dotenv.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("ANTHROPIC_API_KEY 未配置");
        String model = dotenv.get("MODEL_ID");
        if (model == null || model.isBlank()) throw new IllegalStateException("MODEL_ID 未配置");

        AnthropicClient client = buildClient(apiKey, baseUrl);

        String systemPrompt = "You are a coding agent at " + WORKDIR + " (repo root: " + repoRoot + "). "
                + "Use task + worktree tools for multi-task work. "
                + "For parallel or risky changes: create tasks, allocate worktree lanes, "
                + "run commands in those lanes, then choose keep/remove for closeout. "
                + "Use worktree_events when you need lifecycle visibility.";

        // ---- 工具定义（16 个） ----
        List<Tool> tools = List.of(
                defineTool("bash", "Run a shell command.", Map.of("command", Map.of("type","string")), List.of("command")),
                defineTool("read_file", "Read file contents.", Map.of("path",Map.of("type","string"),"limit",Map.of("type","integer")), List.of("path")),
                defineTool("write_file", "Write content to file.", Map.of("path",Map.of("type","string"),"content",Map.of("type","string")), List.of("path","content")),
                defineTool("edit_file", "Replace exact text.", Map.of("path",Map.of("type","string"),"old_text",Map.of("type","string"),"new_text",Map.of("type","string")), List.of("path","old_text","new_text")),
                // 任务工具
                defineTool("task_create", "Create a new task.", Map.of("subject",Map.of("type","string"),"description",Map.of("type","string")), List.of("subject")),
                defineTool("task_list", "List all tasks.", Map.of(), null),
                defineTool("task_get", "Get task details.", Map.of("task_id",Map.of("type","integer")), List.of("task_id")),
                defineTool("task_update", "Update task status or owner.", Map.of("task_id",Map.of("type","integer"),"status",Map.of("type","string","enum",List.of("pending","in_progress","completed")),"owner",Map.of("type","string")), List.of("task_id")),
                defineTool("task_bind_worktree", "Bind task to worktree.", Map.of("task_id",Map.of("type","integer"),"worktree",Map.of("type","string"),"owner",Map.of("type","string")), List.of("task_id","worktree")),
                // Worktree 工具
                defineTool("worktree_create", "Create git worktree.", Map.of("name",Map.of("type","string"),"task_id",Map.of("type","integer"),"base_ref",Map.of("type","string")), List.of("name")),
                defineTool("worktree_list", "List worktrees.", Map.of(), null),
                defineTool("worktree_status", "Show git status for worktree.", Map.of("name",Map.of("type","string")), List.of("name")),
                defineTool("worktree_run", "Run command in worktree.", Map.of("name",Map.of("type","string"),"command",Map.of("type","string")), List.of("name","command")),
                defineTool("worktree_remove", "Remove worktree.", Map.of("name",Map.of("type","string"),"force",Map.of("type","boolean"),"complete_task",Map.of("type","boolean")), List.of("name")),
                defineTool("worktree_keep", "Mark worktree as kept.", Map.of("name",Map.of("type","string")), List.of("name")),
                defineTool("worktree_events", "List recent events.", Map.of("limit",Map.of("type","integer")), null)
        );

        // ---- 工具分发 ----
        Map<String, java.util.function.Function<Map<String,Object>, String>> handlers = new LinkedHashMap<>();
        handlers.put("bash", input -> runBash((String)input.get("command")));
        handlers.put("read_file", input -> runRead((String)input.get("path"), input.get("limit") instanceof Number n ? n.intValue() : null));
        handlers.put("write_file", input -> runWrite((String)input.get("path"), (String)input.get("content")));
        handlers.put("edit_file", input -> runEdit((String)input.get("path"), (String)input.get("old_text"), (String)input.get("new_text")));
        handlers.put("task_create", input -> createTask((String)input.get("subject"), (String)input.getOrDefault("description","")));
        handlers.put("task_list", input -> listTasks());
        handlers.put("task_get", input -> getTask(((Number)input.get("task_id")).intValue()));
        handlers.put("task_update", input -> updateTask(((Number)input.get("task_id")).intValue(), (String)input.get("status"), (String)input.get("owner")));
        handlers.put("task_bind_worktree", input -> bindWorktree(((Number)input.get("task_id")).intValue(), (String)input.get("worktree"), (String)input.getOrDefault("owner","")));
        handlers.put("worktree_create", input -> createWorktree((String)input.get("name"), input.get("task_id") instanceof Number n ? n.intValue() : null, (String)input.get("base_ref")));
        handlers.put("worktree_list", input -> listWorktrees());
        handlers.put("worktree_status", input -> worktreeStatus((String)input.get("name")));
        handlers.put("worktree_run", input -> worktreeRun((String)input.get("name"), (String)input.get("command")));
        handlers.put("worktree_remove", input -> removeWorktree((String)input.get("name"), Boolean.TRUE.equals(input.get("force")), Boolean.TRUE.equals(input.get("complete_task"))));
        handlers.put("worktree_keep", input -> keepWorktree((String)input.get("name")));
        handlers.put("worktree_events", input -> recentEvents(input.get("limit") instanceof Number n ? n.intValue() : 20));

        // ---- REPL ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder().model(model).maxTokens(MAX_TOKENS).system(systemPrompt);
        for (Tool t : tools) paramsBuilder.addTool(t);

        System.out.println("Repo root for s18: " + repoRoot);
        if (!gitAvailable) System.out.println("Note: Not in a git repo. worktree_* tools will return errors.");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print(ansiCyan("s18 >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            if ("/tasks".equals(query)) { System.out.println(listTasks()); continue; }
            if ("/worktrees".equals(query) || "/wt".equals(query)) { System.out.println(listWorktrees()); continue; }
            if ("/events".equals(query)) { System.out.println(recentEvents(10)); continue; }

            paramsBuilder.addUserMessage(query);
            try { agentLoop(client, paramsBuilder, handlers); } catch (Exception e) { System.err.println("Error: "+e.getMessage()); }
            System.out.println();
        }
        System.out.println("Bye!");
    }

    // ==================== Agent 核心循环 ====================

    private static void agentLoop(AnthropicClient client, MessageCreateParams.Builder pb,
                                  Map<String, java.util.function.Function<Map<String,Object>, String>> handlers) {
        while (true) {
            Message resp = client.messages().create(pb.build());
            pb.addMessage(resp);
            if (!resp.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                for (ContentBlock b : resp.content()) b.text().ifPresent(t -> System.out.println(t.text()));
                return;
            }
            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock b : resp.content()) {
                if (!b.isToolUse()) continue;
                ToolUseBlock tu = b.asToolUse();
                @SuppressWarnings("unchecked") Map<String,Object> input = (Map<String,Object>)jsonValueToObject(tu._input());
                if (input == null) input = Map.of();
                var h = handlers.get(tu.name());
                String output;
                try { output = h != null ? h.apply(input) : "Unknown: "+tu.name(); } catch (Exception e) { output = "Error: "+e.getMessage(); }
                System.out.println(ANSI_DIM + "> " + tu.name() + ": " + output.substring(0, Math.min(200, output.length())) + ANSI_RESET);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(output).build()));
            }
            if (!results.isEmpty()) pb.addUserMessageOfBlockParams(results);
        }
    }

    // ==================== Git 检测 ====================

    private static Path detectRepoRoot() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git","rev-parse","--show-toplevel");
            pb.redirectErrorStream(true); Process p = pb.start();
            String o = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0 && !o.isEmpty()) return Path.of(o);
        } catch (Exception ignored) {}
        System.err.println("警告: 无法检测 git 仓库根目录，使用当前工作目录。");
        return WORKDIR;
    }

    private static boolean checkGitRepo() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git","rev-parse","--is-inside-work-tree");
            pb.directory(repoRoot.toFile()); pb.redirectErrorStream(true);
            Process p = pb.start(); return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    private static String runGit(String... args) {
        if (!gitAvailable) throw new RuntimeException("Not in a git repository.");
        try {
            var cmd = new ArrayList<String>(); cmd.add("git"); cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd); pb.directory(repoRoot.toFile()); pb.redirectErrorStream(true);
            Process p = pb.start(); String o = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new RuntimeException("git timeout"); }
            if (p.exitValue() != 0) throw new RuntimeException(o.isEmpty() ? "git failed" : o);
            return o.isEmpty() ? "(no output)" : o;
        } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e.getMessage()); }
    }

    // ==================== EventBus ====================

    private static void emitEvent(String event, Map<String,Object> task, Map<String,Object> worktree, String error) {
        var payload = new LinkedHashMap<String,Object>();
        payload.put("event", event); payload.put("ts", System.currentTimeMillis()/1000.0);
        payload.put("task", task != null ? task : Map.of());
        payload.put("worktree", worktree != null ? worktree : Map.of());
        if (error != null) payload.put("error", error);
        try { Files.writeString(eventsPath, MAPPER.writeValueAsString(payload)+"\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); } catch (IOException ignored) {}
    }

    private static String recentEvents(int limit) {
        try {
            var lines = Files.readAllLines(eventsPath); int n = Math.max(1, Math.min(limit, 200));
            var recent = lines.subList(Math.max(0, lines.size()-n), lines.size());
            var items = new ArrayList<>();
            for (String l : recent) if (!l.isBlank()) try { items.add(MAPPER.readValue(l, Map.class)); } catch (Exception ignored) { items.add(Map.of("event","parse_error","raw",l)); }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(items);
        } catch (IOException e) { return "[]"; }
    }

    // ==================== Task 管理 ====================

    private static int maxTaskId() {
        try (var s = Files.list(tasksDir)) {
            return s.filter(p->p.getFileName().toString().matches("task_\\d+\\.json"))
                    .mapToInt(p->{ String n=p.getFileName().toString(); return Integer.parseInt(n.substring(5,n.length()-5)); }).max().orElse(0);
        } catch (IOException e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> loadTask(int id) {
        Path p = tasksDir.resolve("task_"+id+".json");
        if (!Files.exists(p)) throw new IllegalArgumentException("Task "+id+" not found");
        try { return MAPPER.readValue(Files.readString(p), Map.class); } catch (IOException e) { throw new RuntimeException(e); }
    }

    private static void saveTask(Map<String,Object> task) {
        try { int id=((Number)task.get("id")).intValue(); task.put("updated_at",System.currentTimeMillis()/1000.0);
            Files.writeString(tasksDir.resolve("task_"+id+".json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task));
        } catch (IOException e) { throw new RuntimeException(e); }
    }

    private static synchronized String createTask(String subject, String description) {
        var task = new LinkedHashMap<String,Object>();
        task.put("id", nextTaskId); task.put("subject", subject); task.put("description", description!=null?description:"");
        task.put("status","pending"); task.put("owner",""); task.put("worktree","");
        task.put("created_at",System.currentTimeMillis()/1000.0); task.put("updated_at",System.currentTimeMillis()/1000.0);
        saveTask(task); nextTaskId++;
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task); } catch (IOException e) { return task.toString(); }
    }

    private static String getTask(int id) { try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(loadTask(id)); } catch (Exception e) { return "Error: "+e.getMessage(); } }

    private static synchronized String updateTask(int id, String status, String owner) {
        var task = loadTask(id);
        if (status != null) { if (!List.of("pending","in_progress","completed").contains(status)) return "Error: Invalid status: "+status; task.put("status", status); }
        if (owner != null) task.put("owner", owner);
        saveTask(task);
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task); } catch (IOException e) { return task.toString(); }
    }

    private static synchronized String bindWorktree(int id, String worktree, String owner) {
        var task = loadTask(id); task.put("worktree", worktree);
        if (owner != null && !owner.isEmpty()) task.put("owner", owner);
        if ("pending".equals(task.get("status"))) task.put("status","in_progress");
        saveTask(task);
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task); } catch (IOException e) { return task.toString(); }
    }

    private static synchronized String unbindWorktree(int id) {
        var task = loadTask(id); task.put("worktree",""); saveTask(task);
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task); } catch (IOException e) { return task.toString(); }
    }

    @SuppressWarnings("unchecked")
    private static String listTasks() {
        try (var s = Files.list(tasksDir)) {
            var tasks = s.filter(p->p.getFileName().toString().matches("task_\\d+\\.json")).sorted()
                    .map(p->{ try { return (Map<String,Object>)MAPPER.readValue(Files.readString(p),Map.class); } catch (Exception e) { return null; } })
                    .filter(Objects::nonNull).toList();
            if (tasks.isEmpty()) return "No tasks.";
            var lines = new ArrayList<String>();
            for (var t : tasks) {
                String m = switch((String)t.getOrDefault("status","?")) { case "pending"->"[ ]"; case "in_progress"->"[>]"; case "completed"->"[x]"; default->"[?]"; };
                String owner = t.get("owner")!=null && !t.get("owner").toString().isEmpty() ? " owner="+t.get("owner") : "";
                String wt = t.get("worktree")!=null && !t.get("worktree").toString().isEmpty() ? " wt="+t.get("worktree") : "";
                lines.add(m+" #"+t.get("id")+": "+t.get("subject")+owner+wt);
            }
            return String.join("\n", lines);
        } catch (IOException e) { return "Error: "+e.getMessage(); }
    }

    // ==================== Worktree 管理 ====================

    @SuppressWarnings("unchecked")
    private static Map<String,Object> loadIndex() throws IOException { return MAPPER.readValue(Files.readString(indexPath), Map.class); }
    private static void saveIndex(Map<String,Object> index) throws IOException { Files.writeString(indexPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(index)); }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> findWorktree(String name) throws IOException {
        var index = loadIndex(); var wts = (List<Map<String,Object>>)index.get("worktrees");
        return wts.stream().filter(w->name.equals(w.get("name"))).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static String createWorktree(String name, Integer taskId, String baseRef) {
        if (!NAME_PATTERN.matcher(name).matches()) return "Error: Invalid worktree name. Use 1-40 chars: letters, numbers, ., _, -";
        try {
            if (findWorktree(name) != null) return "Error: Worktree '"+name+"' already exists";
            if (taskId != null && !Files.exists(tasksDir.resolve("task_"+taskId+".json"))) return "Error: Task "+taskId+" not found";
            Path path = worktreeDir.resolve(name); String branch = "wt/"+name; String ref = baseRef != null ? baseRef : "HEAD";
            emitEvent("worktree.create.before", taskId!=null?Map.of("id",taskId):null, Map.of("name",name,"base_ref",ref), null);
            runGit("worktree","add","-b",branch,path.toString(),ref);
            var entry = new LinkedHashMap<String,Object>();
            entry.put("name",name); entry.put("path",path.toString()); entry.put("branch",branch);
            entry.put("task_id",taskId); entry.put("status","active"); entry.put("created_at",System.currentTimeMillis()/1000.0);
            var index = loadIndex(); ((List<Map<String,Object>>)index.get("worktrees")).add(entry); saveIndex(index);
            if (taskId != null) bindWorktree(taskId, name, "");
            emitEvent("worktree.create.after", taskId!=null?Map.of("id",taskId):null, Map.of("name",name,"path",path.toString(),"branch",branch,"status","active"), null);
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entry);
        } catch (IllegalArgumentException e) { return "Error: "+e.getMessage(); }
        catch (Exception e) { emitEvent("worktree.create.failed",null,Map.of("name",name),e.getMessage()); return "Error: "+e.getMessage(); }
    }

    @SuppressWarnings("unchecked")
    private static String listWorktrees() {
        try { var index=loadIndex(); var wts=(List<Map<String,Object>>)index.get("worktrees");
            if (wts.isEmpty()) return "No worktrees in index.";
            var lines = new ArrayList<String>();
            for (var wt : wts) { String suffix = wt.get("task_id")!=null?" task="+wt.get("task_id"):"";
                lines.add("["+wt.getOrDefault("status","unknown")+"] "+wt.get("name")+" -> "+wt.get("path")+" ("+wt.getOrDefault("branch","-")+")"+suffix); }
            return String.join("\n", lines);
        } catch (IOException e) { return "Error: "+e.getMessage(); }
    }

    private static String worktreeStatus(String name) {
        try { var wt=findWorktree(name); if (wt==null) return "Error: Unknown worktree '"+name+"'";
            Path path=Path.of((String)wt.get("path")); if (!Files.exists(path)) return "Error: Path missing: "+path;
            ProcessBuilder pb=new ProcessBuilder("git","status","--short","--branch"); pb.directory(path.toFile()); pb.redirectErrorStream(true);
            Process p=pb.start(); String o=new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(60,TimeUnit.SECONDS)) { p.destroyForcibly(); return "Error: Timeout"; }
            return o.isEmpty()?"Clean worktree":o;
        } catch (Exception e) { return "Error: "+e.getMessage(); }
    }

    private static String worktreeRun(String name, String command) {
        if (command == null || command.isBlank()) return "Error: command required";
        for (String d : List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"))
            if (command.contains(d)) return "Error: Dangerous command blocked";
        try { var wt=findWorktree(name); if (wt==null) return "Error: Unknown worktree '"+name+"'";
            Path path=Path.of((String)wt.get("path")); if (!Files.exists(path)) return "Error: Path missing: "+path;
            ProcessBuilder pb = System.getProperty("os.name").toLowerCase().contains("win")
                ? new ProcessBuilder("cmd", "/c", command)
                : new ProcessBuilder("bash", "-c", command);
            pb.directory(path.toFile()); pb.redirectErrorStream(true);
            Process p=pb.start(); String o=new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(300,TimeUnit.SECONDS)) { p.destroyForcibly(); return "Error: Timeout"; }
            return o.isEmpty()?"(no output)":(o.length()>50000?o.substring(0,50000):o);
        } catch (Exception e) { return "Error: "+e.getMessage(); }
    }

    @SuppressWarnings("unchecked")
    private static String removeWorktree(String name, boolean force, boolean completeTask) {
        try { var wt=findWorktree(name); if (wt==null) return "Error: Unknown worktree '"+name+"'";
            emitEvent("worktree.remove.before", wt.get("task_id")!=null?Map.of("id",wt.get("task_id")):null, Map.of("name",name,"path",wt.getOrDefault("path","")), null);
            var args=new ArrayList<>(List.of("worktree","remove")); if (force) args.add("--force"); args.add((String)wt.get("path"));
            runGit(args.toArray(new String[0]));
            if (completeTask && wt.get("task_id")!=null) { int tid=((Number)wt.get("task_id")).intValue();
                try { updateTask(tid,"completed",null); unbindWorktree(tid); emitEvent("task.completed",Map.of("id",tid,"status","completed"),Map.of("name",name),null); } catch (Exception ignored) {} }
            var index=loadIndex(); for (var item:(List<Map<String,Object>>)index.get("worktrees")) if (name.equals(item.get("name"))) { item.put("status","removed"); item.put("removed_at",System.currentTimeMillis()/1000.0); }
            saveIndex(index);
            emitEvent("worktree.remove.after",wt.get("task_id")!=null?Map.of("id",wt.get("task_id")):null,Map.of("name",name,"status","removed"),null);
            return "Removed worktree '"+name+"'";
        } catch (Exception e) { emitEvent("worktree.remove.failed",null,Map.of("name",name),e.getMessage()); return "Error: "+e.getMessage(); }
    }

    @SuppressWarnings("unchecked")
    private static String keepWorktree(String name) {
        try { var wt=findWorktree(name); if (wt==null) return "Error: Unknown worktree '"+name+"'";
            var index=loadIndex(); Map<String,Object> kept=null;
            for (var item:(List<Map<String,Object>>)index.get("worktrees")) if (name.equals(item.get("name"))) { item.put("status","kept"); item.put("kept_at",System.currentTimeMillis()/1000.0); kept=item; }
            saveIndex(index); emitEvent("worktree.keep",wt.get("task_id")!=null?Map.of("id",wt.get("task_id")):null,Map.of("name",name,"path",wt.get("path"),"status","kept"),null);
            return kept!=null ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(kept) : "Error: Unknown worktree '"+name+"'";
        } catch (Exception e) { return "Error: "+e.getMessage(); }
    }

    // ==================== 基础设施 ====================

    private static AnthropicClient buildClient(String apiKey, String baseUrl) {
        if (baseUrl!=null && !baseUrl.isBlank()) return AnthropicOkHttpClient.builder().apiKey(apiKey).baseUrl(baseUrl).build();
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    private static Tool defineTool(String name, String desc, Map<String,Object> props, List<String> req) {
        var sb = Tool.InputSchema.builder().properties(JsonValue.from(props));
        if (req!=null && !req.isEmpty()) sb.putAdditionalProperty("required", JsonValue.from(req));
        return Tool.builder().name(name).description(desc).inputSchema(sb.build()).build();
    }

    private static Path safePath(String p) { Path r=WORKDIR.resolve(p).normalize().toAbsolutePath(); if (!r.startsWith(WORKDIR)) throw new IllegalArgumentException("Path escapes workspace: "+p); return r; }

    private static String runBash(String cmd) {
        if (cmd==null||cmd.isBlank()) return "Error: command required";
        for (String d: List.of("rm -rf /","sudo","shutdown","reboot","> /dev/")) if (cmd.contains(d)) return "Error: Dangerous command blocked";
        try { ProcessBuilder pb=System.getProperty("os.name").toLowerCase().contains("win") ? new ProcessBuilder("cmd","/c",cmd) : new ProcessBuilder("bash","-c",cmd);
            pb.directory(WORKDIR.toFile()).redirectErrorStream(true); Process p=pb.start();
            String o=new String(p.getInputStream().readAllBytes()).trim(); if (!p.waitFor(BASH_TIMEOUT_SECONDS,TimeUnit.SECONDS)){p.destroyForcibly();return "Error: Timeout";}
            if (o.isEmpty()) return "(no output)"; return o.length()>MAX_OUTPUT ? o.substring(0,MAX_OUTPUT) : o;
        } catch (Exception e) { return "Error: "+e.getMessage(); }
    }

    private static String runRead(String path, Integer limit) {
        try { var lines=Files.readAllLines(safePath(path)); if (limit!=null && limit<lines.size()) { lines=new ArrayList<>(lines.subList(0,limit)); lines.add("... ("+(lines.size()-limit)+" more)"); }
            String r=String.join("\n",lines); return r.length()>MAX_OUTPUT ? r.substring(0,MAX_OUTPUT) : r;
        } catch (Exception e) { return "Error: "+e.getMessage(); }
    }

    private static String runWrite(String path, String content) {
        try { Path fp=safePath(path); Files.createDirectories(fp.getParent()); Files.writeString(fp,content); return "Wrote "+content.length()+" bytes"; } catch (Exception e) { return "Error: "+e.getMessage(); }
    }

    private static String runEdit(String path, String oldT, String newT) {
        try { Path fp=safePath(path); String c=Files.readString(fp); if (!c.contains(oldT)) return "Error: Text not found"; int idx=c.indexOf(oldT); Files.writeString(fp,c.substring(0,idx)+newT+c.substring(idx+oldT.length())); return "Edited "+path; } catch (Exception e) { return "Error: "+e.getMessage(); }
    }

    @SuppressWarnings("unchecked")
    private static Object jsonValueToObject(JsonValue value) {
        if (value==null) return null; var s=value.asString(); if (s.isPresent()) return s.get();
        var n=value.asNumber(); if (n.isPresent()) return n.get(); var b=value.asBoolean(); if (b.isPresent()) return b.get();
        try { var m=value.asObject(); if (m.isPresent()) { Map<String,JsonValue> raw=(Map<String,JsonValue>)(Object)m.get(); var r=new LinkedHashMap<String,Object>(); for (var e:raw.entrySet()) r.put(e.getKey(),jsonValueToObject(e.getValue())); return r; } } catch (ClassCastException ignored) {}
        try { var l=value.asArray(); if (l.isPresent()) { List<JsonValue> raw=(List<JsonValue>)(Object)l.get(); var r=new ArrayList<>(); for (JsonValue i:raw) r.add(jsonValueToObject(i)); return r; } } catch (ClassCastException ignored) {}
        return null;
    }

    private static String ansiCyan(String t) { return ANSI_CYAN+t+ANSI_RESET; }
}
