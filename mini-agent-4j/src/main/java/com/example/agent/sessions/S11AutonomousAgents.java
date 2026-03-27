package com.example.agent.sessions;

import com.anthropic.models.messages.*;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.tasks.TaskManager;
import com.example.agent.team.MessageBus;
import com.example.agent.team.TeamManager;
import com.example.agent.team.TeamProtocol;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.PathSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * S11：自治 Agent —— 在 S10 基础上增加 idle 轮询和任务认领。
 * <p>
 * 关键进化：Teammate 不再只被动接收指令，而是主动从任务池中认领任务。
 * <pre>
 * Lead:     创建任务 → 放入 .tasks/ 池
 * Teammate: idle 轮询 → 发现未分配任务 → claim_task → 执行 → 完成
 * </pre>
 * <p>
 * 新增工具：
 * - idle: Teammate 空闲时调用，触发轮询逻辑
 * - claim_task: 认领一个 pending 任务并设为 in_progress
 * <p>
 * REPL 命令：/team, /inbox, /tasks
 * <p>
 * 关键洞察："自治 = 有工作就做，没工作就等。Lead 只管发任务，不管分配。"
 * <p>
 * 对应 Python 原版：s11_autonomous_agents.py
 */
public class S11AutonomousAgents {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);
        Path teamDir = workDir.resolve(".team");
        MessageBus bus = new MessageBus(teamDir.resolve("inbox"));
        TeamProtocol protocol = new TeamProtocol(bus);
        TaskManager taskMgr = new TaskManager(workDir.resolve(".tasks"));

        String systemPrompt = "You are a team lead at " + workDir
                + ". Spawn autonomous teammates that poll for tasks. "
                + "Create tasks with task_create, teammates will claim and execute them.";

        // ---- 工具定义（S10 + idle + claim_task + task 工具） ----
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
                AgentLoop.defineTool("spawn_teammate",
                        "Spawn an autonomous teammate (polls for tasks).",
                        Map.of("name", Map.of("type", "string"),
                                "role", Map.of("type", "string"),
                                "prompt", Map.of("type", "string")),
                        List.of("name", "role", "prompt")),
                AgentLoop.defineTool("list_teammates", "List all teammates and their status.",
                        Map.of(), null),
                AgentLoop.defineTool("send_message", "Send a message to a teammate's inbox.",
                        Map.of("to", Map.of("type", "string"),
                                "content", Map.of("type", "string")),
                        List.of("to", "content")),
                AgentLoop.defineTool("read_inbox", "Read and drain the lead's inbox.",
                        Map.of(), null),
                AgentLoop.defineTool("broadcast", "Broadcast a message to all teammates.",
                        Map.of("content", Map.of("type", "string")),
                        List.of("content")),
                // 协议工具
                AgentLoop.defineTool("shutdown_request",
                        "Send a graceful shutdown request to a teammate.",
                        Map.of("teammate", Map.of("type", "string")),
                        List.of("teammate")),
                AgentLoop.defineTool("shutdown_response",
                        "Check shutdown request status by request_id.",
                        Map.of("request_id", Map.of("type", "string")),
                        List.of("request_id")),
                AgentLoop.defineTool("plan_approval",
                        "Review a teammate's plan.",
                        Map.of("request_id", Map.of("type", "string"),
                                "approve", Map.of("type", "boolean"),
                                "feedback", Map.of("type", "string")),
                        List.of("request_id", "approve")),
                // 任务工具
                AgentLoop.defineTool("task_create", "Create a new task in the pool.",
                        Map.of("subject", Map.of("type", "string"),
                                "description", Map.of("type", "string")),
                        List.of("subject")),
                AgentLoop.defineTool("task_list", "List all tasks with status summary.",
                        Map.of(), null),
                AgentLoop.defineTool("task_get", "Get full details of a task by ID.",
                        Map.of("task_id", Map.of("type", "integer")),
                        List.of("task_id")),
                AgentLoop.defineTool("task_update", "Update a task's status.",
                        Map.of("task_id", Map.of("type", "integer"),
                                "status", Map.of("type", "string",
                                        "enum", List.of("pending", "in_progress", "completed"))),
                        List.of("task_id")),
                // 自治工具
                AgentLoop.defineTool("idle",
                        "Signal that you are idle. The harness will check for pending tasks.",
                        Map.of(), null),
                AgentLoop.defineTool("claim_task",
                        "Claim a pending task by ID. Sets owner and status to in_progress.",
                        Map.of("task_id", Map.of("type", "integer"),
                                "owner", Map.of("type", "string")),
                        List.of("task_id", "owner"))
        );

        // ---- AgentLoop ----
        ToolDispatcher dispatcher = new ToolDispatcher();
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);

        // ---- TeamManager ----
        TeamManager teamMgr = new TeamManager(teamDir, bus,
                agent.getClient(), agent.getModel(), workDir, sandbox);

        // ---- 工具分发器 ----
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
        dispatcher.register("spawn_teammate", input ->
                teamMgr.spawn((String) input.get("name"),
                        (String) input.get("role"),
                        (String) input.get("prompt")));
        dispatcher.register("list_teammates", input -> teamMgr.listAll());
        dispatcher.register("send_message", input ->
                bus.send("lead", (String) input.get("to"),
                        (String) input.get("content"), "message", null));
        dispatcher.register("read_inbox", input -> {
            try {
                var messages = bus.readInbox("lead");
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(messages);
            } catch (Exception e) {
                return "[]";
            }
        });
        dispatcher.register("broadcast", input ->
                bus.broadcast("lead", (String) input.get("content"), teamMgr.memberNames()));
        dispatcher.register("shutdown_request", input ->
                protocol.requestShutdown((String) input.get("teammate")));
        dispatcher.register("shutdown_response", input ->
                protocol.checkShutdownStatus((String) input.get("request_id")));
        dispatcher.register("plan_approval", input ->
                protocol.reviewPlan((String) input.get("request_id"),
                        Boolean.TRUE.equals(input.get("approve")),
                        (String) input.get("feedback")));
        // 任务工具分发
        dispatcher.register("task_create", input ->
                taskMgr.create((String) input.get("subject"),
                        (String) input.getOrDefault("description", "")));
        dispatcher.register("task_list", input -> taskMgr.listAll());
        dispatcher.register("task_get", input ->
                taskMgr.get(((Number) input.get("task_id")).intValue()));
        dispatcher.register("task_update", input ->
                taskMgr.update(((Number) input.get("task_id")).intValue(),
                        (String) input.get("status"), null, null));
        // 自治工具分发
        dispatcher.register("idle", input -> {
            // idle 工具：检查是否有未分配的 pending 任务
            String taskList = taskMgr.listAll();
            if (taskList.contains("[ ]")) {
                return "There are pending tasks available. Use claim_task to pick one.\n" + taskList;
            }
            return "No pending tasks. Waiting...";
        });
        dispatcher.register("claim_task", input ->
                taskMgr.claim(((Number) input.get("task_id")).intValue(),
                        (String) input.get("owner")));

        // ---- REPL ----
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\033[36ms11 >> \033[0m");
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            // REPL 斜杠命令
            if ("/team".equals(query)) {
                System.out.println(teamMgr.listAll());
                continue;
            }
            if ("/inbox".equals(query)) {
                try {
                    var msgs = bus.readInbox("lead");
                    System.out.println(msgs.isEmpty() ? "Inbox empty."
                            : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(msgs));
                } catch (Exception e) {
                    System.out.println("Inbox empty.");
                }
                continue;
            }
            if ("/tasks".equals(query)) {
                System.out.println(taskMgr.listAll());
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
}
