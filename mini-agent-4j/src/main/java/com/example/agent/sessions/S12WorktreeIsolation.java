package com.example.agent.sessions;

import com.anthropic.models.messages.*;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.tasks.TaskManager;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.PathSandbox;
import com.example.agent.worktree.WorktreeManager;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * S12：Git Worktree 隔离 —— 目录级并行任务执行。
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
 * 对应 Python 原版：s12_worktree_task_isolation.py
 */
public class S12WorktreeIsolation {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        // ---- 检测 git 仓库根目录 ----
        Path repoRoot = detectRepoRoot();
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);
        TaskManager taskMgr = new TaskManager(workDir.resolve(".tasks"));
        WorktreeManager wtMgr = new WorktreeManager(repoRoot);

        if (!wtMgr.isGitAvailable()) {
            System.err.println("警告: 当前目录不在 git 仓库中，worktree 功能不可用。");
        }

        String systemPrompt = "You are a coding agent at " + workDir
                + " (repo root: " + repoRoot + "). "
                + "Use worktree tools to isolate work in separate git worktrees. "
                + "Use task tools to track what needs to be done.";

        // ---- 工具定义 ----
        List<Tool> tools = List.of(
                AgentLoop.defineTool("bash", "Run a shell command in the main working directory.",
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
                AgentLoop.defineTool("task_create", "Create a new task.",
                        Map.of("subject", Map.of("type", "string"),
                                "description", Map.of("type", "string")),
                        List.of("subject")),
                AgentLoop.defineTool("task_list", "List all tasks with status summary.",
                        Map.of(), null),
                AgentLoop.defineTool("task_get", "Get full details of a task by ID.",
                        Map.of("task_id", Map.of("type", "integer")),
                        List.of("task_id")),
                AgentLoop.defineTool("task_update", "Update a task's status or dependencies.",
                        Map.of("task_id", Map.of("type", "integer"),
                                "status", Map.of("type", "string",
                                        "enum", List.of("pending", "in_progress", "completed")),
                                "addBlockedBy", Map.of("type", "array",
                                        "items", Map.of("type", "integer")),
                                "addBlocks", Map.of("type", "array",
                                        "items", Map.of("type", "integer"))),
                        List.of("task_id")),
                // ---- Worktree 工具 ----
                AgentLoop.defineTool("worktree_create",
                        "Create a new git worktree. Optionally bind to a task_id.",
                        Map.of("name", Map.of("type", "string"),
                                "task_id", Map.of("type", "integer"),
                                "base_ref", Map.of("type", "string")),
                        List.of("name")),
                AgentLoop.defineTool("worktree_list", "List all worktrees with status.",
                        Map.of(), null),
                AgentLoop.defineTool("worktree_status",
                        "Show git status of a worktree (runs 'git status' inside it).",
                        Map.of("name", Map.of("type", "string")),
                        List.of("name")),
                AgentLoop.defineTool("worktree_run",
                        "Run a shell command inside a worktree directory.",
                        Map.of("name", Map.of("type", "string"),
                                "command", Map.of("type", "string")),
                        List.of("name", "command")),
                AgentLoop.defineTool("worktree_remove",
                        "Remove a worktree (delete directory and branch).",
                        Map.of("name", Map.of("type", "string"),
                                "force", Map.of("type", "boolean")),
                        List.of("name")),
                AgentLoop.defineTool("worktree_keep",
                        "Mark a worktree as kept (do not auto-remove).",
                        Map.of("name", Map.of("type", "string")),
                        List.of("name")),
                AgentLoop.defineTool("worktree_events",
                        "Show recent worktree lifecycle events.",
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
                taskMgr.create((String) input.get("subject"),
                        (String) input.getOrDefault("description", "")));
        dispatcher.register("task_list", input -> taskMgr.listAll());
        dispatcher.register("task_get", input ->
                taskMgr.get(((Number) input.get("task_id")).intValue()));
        dispatcher.register("task_update", input ->
                taskMgr.update(((Number) input.get("task_id")).intValue(),
                        (String) input.get("status"),
                        (List<Integer>) input.get("addBlockedBy"),
                        (List<Integer>) input.get("addBlocks")));
        // Worktree 工具
        dispatcher.register("worktree_create", input ->
                wtMgr.create((String) input.get("name"),
                        input.get("task_id") instanceof Number n ? n.intValue() : null,
                        (String) input.get("base_ref")));
        dispatcher.register("worktree_list", input -> wtMgr.listAll());
        dispatcher.register("worktree_status", input ->
                wtMgr.run((String) input.get("name"), "git status"));
        dispatcher.register("worktree_run", input ->
                wtMgr.run((String) input.get("name"), (String) input.get("command")));
        dispatcher.register("worktree_remove", input ->
                wtMgr.remove((String) input.get("name"),
                        Boolean.TRUE.equals(input.get("force")), false));
        dispatcher.register("worktree_keep", input ->
                wtMgr.run((String) input.get("name"), "echo 'Worktree kept: " + input.get("name") + "'"));
        dispatcher.register("worktree_events", input ->
                wtMgr.recentEvents(input.get("limit") instanceof Number n ? n.intValue() : 20));

        // ---- REPL ----
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\033[36ms12 >> \033[0m");
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            // REPL 斜杠命令
            if ("/tasks".equals(query)) {
                System.out.println(taskMgr.listAll());
                continue;
            }
            if ("/worktrees".equals(query) || "/wt".equals(query)) {
                System.out.println(wtMgr.listAll());
                continue;
            }
            if ("/events".equals(query)) {
                System.out.println(wtMgr.recentEvents(10));
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

    /**
     * 检测 git 仓库根目录。
     * <p>
     * 执行 {@code git rev-parse --show-toplevel}，失败则回退到当前工作目录。
     */
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
        // 回退到当前工作目录
        System.err.println("警告: 无法检测 git 仓库根目录，使用当前工作目录。");
        return Path.of(System.getProperty("user.dir"));
    }
}
