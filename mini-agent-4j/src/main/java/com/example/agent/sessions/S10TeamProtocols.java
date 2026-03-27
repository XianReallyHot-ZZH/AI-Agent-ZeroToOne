package com.example.agent.sessions;

import com.anthropic.models.messages.*;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
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
 * S10：团队协议 —— 在 S09 基础上增加 shutdown 和 plan approval 握手机制。
 * <p>
 * 两种协议：
 * <pre>
 * Shutdown:      Lead → shutdown_request(req_id) → Teammate
 *                Teammate → shutdown_response(req_id, approve) → Lead
 *
 * Plan Approval: Teammate → plan_approval(req_id, plan) → Lead
 *                Lead → plan_approval_response(req_id, approve, feedback) → Teammate
 * </pre>
 * <p>
 * 关键洞察："协议 = 消息类型 + request_id 关联 + 状态机跟踪。"
 * <p>
 * REPL 命令：/team, /inbox
 * <p>
 * 对应 Python 原版：s10_team_protocols.py
 */
public class S10TeamProtocols {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);
        Path teamDir = workDir.resolve(".team");
        MessageBus bus = new MessageBus(teamDir.resolve("inbox"));
        TeamProtocol protocol = new TeamProtocol(bus);

        String systemPrompt = "You are a team lead at " + workDir
                + ". Spawn teammates and communicate via inboxes. "
                + "Use shutdown_request to gracefully stop a teammate. "
                + "Use plan_approval to review teammate plans.";

        // ---- 工具定义（在 S09 基础上增加协议工具） ----
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
                        "Spawn a persistent teammate (runs in background thread).",
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
                // ---- 协议工具 ----
                AgentLoop.defineTool("shutdown_request",
                        "Send a graceful shutdown request to a teammate. Returns request_id.",
                        Map.of("teammate", Map.of("type", "string")),
                        List.of("teammate")),
                AgentLoop.defineTool("shutdown_response",
                        "Check shutdown request status by request_id.",
                        Map.of("request_id", Map.of("type", "string")),
                        List.of("request_id")),
                AgentLoop.defineTool("plan_approval",
                        "Review a teammate's plan. Approve or reject with feedback.",
                        Map.of("request_id", Map.of("type", "string"),
                                "approve", Map.of("type", "boolean"),
                                "feedback", Map.of("type", "string")),
                        List.of("request_id", "approve"))
        );

        // ---- AgentLoop（用于获取 client 和 model） ----
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
        // ---- 协议工具分发 ----
        dispatcher.register("shutdown_request", input ->
                protocol.requestShutdown((String) input.get("teammate")));
        dispatcher.register("shutdown_response", input ->
                protocol.checkShutdownStatus((String) input.get("request_id")));
        dispatcher.register("plan_approval", input ->
                protocol.reviewPlan((String) input.get("request_id"),
                        Boolean.TRUE.equals(input.get("approve")),
                        (String) input.get("feedback")));

        // ---- REPL ----
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\033[36ms10 >> \033[0m");
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
