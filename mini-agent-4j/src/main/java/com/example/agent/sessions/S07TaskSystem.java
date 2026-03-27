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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * S07：任务系统 —— 持久化到文件系统的目标，不怕上下文压缩。
 * <p>
 * 任务以 JSON 文件存储在 .tasks/ 目录，具备依赖图（blockedBy/blocks）。
 * 完成任务时自动解除其他任务的依赖。
 * <p>
 * 关键洞察："状态存在对话之外——因为它在文件系统上。"
 * <p>
 * 对应 Python 原版：s07_task_system.py
 */
public class S07TaskSystem {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);
        TaskManager taskMgr = new TaskManager(workDir.resolve(".tasks"));

        String systemPrompt = "You are a coding agent at " + workDir
                + ". Use task tools to plan and track work.";

        // ---- 工具定义 ----
        List<Tool> tools = List.of(
                AgentLoop.defineTool("bash", "Run a shell command.",
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
                AgentLoop.defineTool("task_create", "Create a new task.",
                        Map.of("subject", Map.of("type", "string"),
                                "description", Map.of("type", "string")),
                        List.of("subject")),
                AgentLoop.defineTool("task_update", "Update a task's status or dependencies.",
                        Map.of("task_id", Map.of("type", "integer"),
                                "status", Map.of("type", "string",
                                        "enum", List.of("pending", "in_progress", "completed")),
                                "addBlockedBy", Map.of("type", "array",
                                        "items", Map.of("type", "integer")),
                                "addBlocks", Map.of("type", "array",
                                        "items", Map.of("type", "integer"))),
                        List.of("task_id")),
                AgentLoop.defineTool("task_list", "List all tasks with status summary.",
                        Map.of(), null),
                AgentLoop.defineTool("task_get", "Get full details of a task by ID.",
                        Map.of("task_id", Map.of("type", "integer")),
                        List.of("task_id"))
        );

        // ---- 工具分发器 ----
        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
        dispatcher.register("task_create", input ->
                taskMgr.create((String) input.get("subject"),
                        (String) input.getOrDefault("description", "")));
        dispatcher.register("task_update", input ->
                taskMgr.update(((Number) input.get("task_id")).intValue(),
                        (String) input.get("status"),
                        (List<Integer>) input.get("addBlockedBy"),
                        (List<Integer>) input.get("addBlocks")));
        dispatcher.register("task_list", input -> taskMgr.listAll());
        dispatcher.register("task_get", input ->
                taskMgr.get(((Number) input.get("task_id")).intValue()));

        // ---- REPL ----
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\033[36ms07 >> \033[0m");
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;
            paramsBuilder.addUserMessage(query);
            try {
                agent.agentLoop(paramsBuilder);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
    }
}
