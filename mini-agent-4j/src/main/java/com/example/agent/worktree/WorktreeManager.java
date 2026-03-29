package com.example.agent.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Git Worktree 管理器：目录级隔离实现并行任务执行。
 * <p>
 * 任务是控制平面，Worktree 是执行平面。
 * 每个 Worktree 是一个独立的 git 工作副本，可绑定到一个任务。
 * <p>
 * 生命周期：active → kept / removed
 * <pre>
 * .worktrees/
 *   index.json           ← Worktree 索引
 *   events.jsonl          ← 生命周期事件日志
 *   auth-refactor/        ← 实际的 git worktree 目录
 * </pre>
 * <p>
 * 关键洞察："按目录隔离，按任务 ID 协调。"
 * <p>
 * 对应 Python 原版：s12_worktree_task_isolation.py
 */
public class WorktreeManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Worktree 名称正则：1-40 个字母/数字/点/下划线/横线 */
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    private final Path repoRoot;
    private final Path worktreeDir;
    private final Path indexPath;
    private final Path eventsPath;
    private final boolean gitAvailable;

    public WorktreeManager(Path repoRoot) {
        this.repoRoot = repoRoot;
        this.worktreeDir = repoRoot.resolve(".worktrees");
        this.indexPath = worktreeDir.resolve("index.json");
        this.eventsPath = worktreeDir.resolve("events.jsonl");

        try {
            Files.createDirectories(worktreeDir);
            if (!Files.exists(indexPath)) {
                Files.writeString(indexPath, MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(Map.of("worktrees", new ArrayList<>())));
            }
            if (!Files.exists(eventsPath)) {
                Files.writeString(eventsPath, "");
            }
        } catch (IOException ignored) {}

        this.gitAvailable = checkGitRepo();
    }

    private boolean checkGitRepo() {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree");
            pb.directory(repoRoot.toFile());
            Process p = pb.start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String runGit(String... args) {
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

    /** 事件日志记录 */
    private void emitEvent(String event, Map<String, Object> task, Map<String, Object> worktree, String error) {
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadIndex() throws IOException {
        return MAPPER.readValue(Files.readString(indexPath), Map.class);
    }

    private void saveIndex(Map<String, Object> index) throws IOException {
        Files.writeString(indexPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(index));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findWorktree(String name) throws IOException {
        var index = loadIndex();
        var wts = (List<Map<String, Object>>) index.get("worktrees");
        return wts.stream().filter(w -> name.equals(w.get("name"))).findFirst().orElse(null);
    }

    /**
     * 创建 git worktree。
     */
    @SuppressWarnings("unchecked")
    public String create(String name, Integer taskId, String baseRef) {
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid worktree name. Use 1-40 chars: letters, numbers, ., _, -");
        }
        try {
            if (findWorktree(name) != null) throw new IllegalArgumentException("Worktree '" + name + "' already exists");
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

            emitEvent("worktree.create.after",
                    taskId != null ? Map.of("id", taskId) : null,
                    Map.of("name", name, "path", path.toString(), "branch", branch, "status", "active"), null);

            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entry);
        } catch (Exception e) {
            emitEvent("worktree.create.failed", null, Map.of("name", name), e.getMessage());
            throw new RuntimeException(e.getMessage());
        }
    }

    /**
     * 在 worktree 目录中执行命令。
     */
    public String run(String name, String command) {
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

    /**
     * 移除 worktree。
     */
    @SuppressWarnings("unchecked")
    public String remove(String name, boolean force, boolean completeTask) {
        try {
            var wt = findWorktree(name);
            if (wt == null) return "Error: Unknown worktree '" + name + "'";
            emitEvent("worktree.remove.before", null, Map.of("name", name, "path", wt.getOrDefault("path", "")), null);

            var args = new ArrayList<>(List.of("worktree", "remove"));
            if (force) args.add("--force");
            args.add((String) wt.get("path"));
            runGit(args.toArray(new String[0]));

            var index = loadIndex();
            for (var item : (List<Map<String, Object>>) index.get("worktrees")) {
                if (name.equals(item.get("name"))) {
                    item.put("status", "removed");
                    item.put("removed_at", System.currentTimeMillis() / 1000.0);
                }
            }
            saveIndex(index);

            emitEvent("worktree.remove.after", null, Map.of("name", name, "status", "removed"), null);
            return "Removed worktree '" + name + "'";
        } catch (Exception e) {
            emitEvent("worktree.remove.failed", null, Map.of("name", name), e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 列出所有 worktree。
     */
    @SuppressWarnings("unchecked")
    public String listAll() {
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

    /**
     * 获取最近的事件日志。
     */
    public String recentEvents(int limit) {
        try {
            var lines = Files.readAllLines(eventsPath);
            var recent = lines.subList(Math.max(0, lines.size() - limit), lines.size());
            var items = new ArrayList<Object>();
            for (String line : recent) {
                if (!line.isBlank()) items.add(MAPPER.readValue(line, Map.class));
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(items);
        } catch (IOException e) {
            return "[]";
        }
    }

    public boolean isGitAvailable() { return gitAvailable; }
}
