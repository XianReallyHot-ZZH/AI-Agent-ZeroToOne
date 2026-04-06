package com.example.agent.sessions;

import com.anthropic.models.messages.*;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.team.MessageBus;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S10：团队协议 —— 自包含实现（不依赖 TeamManager / TeamProtocol）。
 * <p>
 * 在 S09 基础上增加 shutdown 和 plan approval 两种握手协议。
 * 两种协议都使用 request_id 关联请求与响应：
 * <pre>
 * Shutdown:     Lead → shutdown_request(req_id) → Teammate
 *               Teammate → shutdown_response(req_id, approve) → Lead
 *
 * Plan Approval: Teammate → plan_approval(req_id, plan) → Lead
 *                Lead → plan_approval_response(req_id, approve) → Teammate
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

    // ---- Protocol trackers: request_id → {target/from, status, ...} ----
    private static final ConcurrentHashMap<String, Map<String, Object>> shutdownRequests = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Map<String, Object>> planRequests = new ConcurrentHashMap<>();

    // ---- Team state ----
    private static Path workDir;
    private static PathSandbox sandbox;
    private static Path teamDir;
    private static Path configPath;
    private static Map<String, Object> teamConfig;
    private static MessageBus bus;

    // ---- LLM (从 AgentLoop 获取，供 teammate 直接调用) ----
    private static com.anthropic.client.AnthropicClient client;
    private static String model;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        workDir = Path.of(System.getProperty("user.dir"));
        sandbox = new PathSandbox(workDir);
        teamDir = workDir.resolve(".team");
        configPath = teamDir.resolve("config.json");
        bus = new MessageBus(teamDir.resolve("inbox"));
        teamConfig = loadTeamConfig();

        String systemPrompt = "You are a team lead at " + workDir
                + ". Manage teammates with shutdown and plan approval protocols.";

        // ---- Lead 工具定义（12 个） ----
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
                                "content", Map.of("type", "string"),
                                "msg_type", Map.of("type", "string",
                                        "enum", List.of("message", "broadcast", "shutdown_request",
                                                "shutdown_response", "plan_approval_response"))),
                        List.of("to", "content")),
                AgentLoop.defineTool("read_inbox", "Read and drain the lead's inbox.",
                        Map.of(), null),
                AgentLoop.defineTool("broadcast", "Broadcast a message to all teammates.",
                        Map.of("content", Map.of("type", "string")),
                        List.of("content")),
                // ---- 协议工具 ----
                AgentLoop.defineTool("shutdown_request",
                        "Request a teammate to shut down gracefully. Returns request_id for tracking.",
                        Map.of("teammate", Map.of("type", "string")),
                        List.of("teammate")),
                AgentLoop.defineTool("shutdown_response",
                        "Check the status of a shutdown request by request_id.",
                        Map.of("request_id", Map.of("type", "string")),
                        List.of("request_id")),
                AgentLoop.defineTool("plan_approval",
                        "Approve or reject a teammate's plan by request_id.",
                        Map.of("request_id", Map.of("type", "string"),
                                "approve", Map.of("type", "boolean"),
                                "feedback", Map.of("type", "string")),
                        List.of("request_id", "approve"))
        );

        // ---- AgentLoop（Lead 的主循环） ----
        ToolDispatcher dispatcher = new ToolDispatcher();
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);

        // 从 AgentLoop 获取 client/model，供 teammate 直接 API 调用
        client = agent.getClient();
        model = agent.getModel();

        // ---- Lead 工具分发 ----
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
        dispatcher.register("spawn_teammate", input ->
                spawnTeammate((String) input.get("name"),
                        (String) input.get("role"),
                        (String) input.get("prompt")));
        dispatcher.register("list_teammates", input -> listAll());
        dispatcher.register("send_message", input ->
                bus.send("lead", (String) input.get("to"),
                        (String) input.get("content"),
                        (String) input.getOrDefault("msg_type", "message"), null));
        dispatcher.register("read_inbox", input -> {
            try {
                var messages = bus.readInbox("lead");
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(messages);
            } catch (Exception e) {
                return "[]";
            }
        });
        dispatcher.register("broadcast", input ->
                bus.broadcast("lead", (String) input.get("content"), memberNames()));
        // ---- 协议工具分发 ----
        dispatcher.register("shutdown_request", input ->
                handleShutdownRequest((String) input.get("teammate")));
        dispatcher.register("shutdown_response", input ->
                checkShutdownStatus((String) input.get("request_id")));
        dispatcher.register("plan_approval", input ->
                handlePlanReview((String) input.get("request_id"),
                        Boolean.TRUE.equals(input.get("approve")),
                        (String) input.get("feedback")));

        // ---- Lead inbox 自动注入 ----
        agent.setPreLLMHook(() -> {
            var inbox = bus.readInbox("lead");
            if (inbox.isEmpty()) return null;
            var blocks = new ArrayList<ContentBlockParam>();
            try {
                blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                        .text("<inbox>" + MAPPER.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(inbox) + "</inbox>")
                        .build()));
            } catch (Exception ignored) {}
            return blocks;
        });

        // ---- REPL ----
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print(Console.cyan("s10 >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            if ("/team".equals(query)) {
                System.out.println(listAll());
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

    // ==================== Team config 管理 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadTeamConfig() {
        try {
            Files.createDirectories(teamDir);
        } catch (IOException ignored) {}
        if (Files.exists(configPath)) {
            try {
                return MAPPER.readValue(Files.readString(configPath), Map.class);
            } catch (IOException ignored) {}
        }
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("team_name", "default");
        cfg.put("members", new ArrayList<Map<String, Object>>());
        return cfg;
    }

    private static synchronized void saveTeamConfig() {
        try {
            Files.writeString(configPath,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(teamConfig));
        } catch (IOException ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static synchronized Map<String, Object> findMember(String name) {
        var members = (List<Map<String, Object>>) teamConfig.get("members");
        return members.stream()
                .filter(m -> name.equals(m.get("name")))
                .findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static String listAll() {
        var members = (List<Map<String, Object>>) teamConfig.get("members");
        if (members.isEmpty()) return "No teammates.";
        var lines = new ArrayList<String>();
        lines.add("Team: " + teamConfig.get("team_name"));
        for (var m : members) {
            lines.add("  " + m.get("name") + " (" + m.get("role") + "): " + m.get("status"));
        }
        return String.join("\n", lines);
    }

    @SuppressWarnings("unchecked")
    private static List<String> memberNames() {
        var members = (List<Map<String, Object>>) teamConfig.get("members");
        return members.stream().map(m -> (String) m.get("name")).toList();
    }

    // ==================== Teammate 管理 ====================

    @SuppressWarnings("unchecked")
    private static synchronized String spawnTeammate(String name, String role, String prompt) {
        var member = findMember(name);
        if (member != null) {
            String status = (String) member.get("status");
            if (!"idle".equals(status) && !"shutdown".equals(status)) {
                return "Error: '" + name + "' is currently " + status;
            }
            member.put("status", "working");
            member.put("role", role);
        } else {
            member = new LinkedHashMap<>(Map.of("name", name, "role", role, "status", "working"));
            ((List<Map<String, Object>>) teamConfig.get("members")).add(member);
        }
        saveTeamConfig();

        Thread.ofVirtual().name("agent-" + name)
                .start(() -> teammateLoop(name, role, prompt));

        return "Spawned '" + name + "' (role: " + role + ")";
    }

    /**
     * Teammate 工作循环（自包含，8 个工具含协议）。
     * <p>
     * 对应 Python: TeammateManager._teammate_loop
     * <ul>
     *   <li>6 base 工具 + shutdown_response + plan_approval</li>
     *   <li>should_exit 追踪：shutdown_response(approve=true) 后退出</li>
     *   <li>最终状态：shutdown（协议退出）或 idle（正常结束）</li>
     * </ul>
     */
    private static void teammateLoop(String name, String role, String prompt) {
        String sysPrompt = "You are '" + name + "', role: " + role + ", at " + workDir + ". "
                + "Submit plans via plan_approval before major work. "
                + "Respond to shutdown_request with shutdown_response.";

        var paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000)
                .system(sysPrompt);

        // Teammate 工具集（8 个）：6 base + shutdown_response + plan_approval
        List<Tool> tools = List.of(
                AgentLoop.defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
                AgentLoop.defineTool("read_file", "Read file contents.",
                        Map.of("path", Map.of("type", "string")), List.of("path")),
                AgentLoop.defineTool("write_file", "Write content to file.",
                        Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")),
                        List.of("path", "content")),
                AgentLoop.defineTool("edit_file", "Replace exact text in file.",
                        Map.of("path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),
                AgentLoop.defineTool("send_message", "Send message to a teammate.",
                        Map.of("to", Map.of("type", "string"),
                                "content", Map.of("type", "string"),
                                "msg_type", Map.of("type", "string",
                                        "enum", List.of("message", "broadcast", "shutdown_request",
                                                "shutdown_response", "plan_approval_response"))),
                        List.of("to", "content")),
                AgentLoop.defineTool("read_inbox", "Read and drain your inbox.",
                        Map.of(), null),
                // ---- 协议工具 ----
                AgentLoop.defineTool("shutdown_response",
                        "Respond to a shutdown request. Approve to shut down, reject to keep working.",
                        Map.of("request_id", Map.of("type", "string"),
                                "approve", Map.of("type", "boolean"),
                                "reason", Map.of("type", "string")),
                        List.of("request_id", "approve")),
                AgentLoop.defineTool("plan_approval",
                        "Submit a plan for lead approval. Provide plan text.",
                        Map.of("plan", Map.of("type", "string")),
                        List.of("plan"))
        );
        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }
        paramsBuilder.addUserMessage(prompt);

        // Teammate 工具分发器
        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
        dispatcher.register("send_message", input ->
                bus.send(name, (String) input.get("to"), (String) input.get("content"),
                        (String) input.getOrDefault("msg_type", "message"), null));
        dispatcher.register("read_inbox", input -> {
            try {
                return MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(bus.readInbox(name));
            } catch (IOException e) {
                return "[]";
            }
        });
        // ---- 协议工具分发 ----
        dispatcher.register("shutdown_response", input -> {
            String reqId = (String) input.get("request_id");
            boolean approve = Boolean.TRUE.equals(input.get("approve"));
            String reason = (String) input.getOrDefault("reason", "");
            // 更新 tracker
            var req = shutdownRequests.get(reqId);
            if (req != null) {
                req.put("status", approve ? "approved" : "rejected");
            }
            // 发送响应给 lead
            bus.send(name, "lead", reason, "shutdown_response",
                    Map.of("request_id", reqId, "approve", approve));
            return "Shutdown " + (approve ? "approved" : "rejected");
        });
        dispatcher.register("plan_approval", input -> {
            String planText = (String) input.get("plan");
            String reqId = UUID.randomUUID().toString().substring(0, 8);
            planRequests.put(reqId, new ConcurrentHashMap<>(Map.of(
                    "from", name, "plan", planText, "status", "pending")));
            bus.send(name, "lead", planText, "plan_approval_response",
                    Map.of("request_id", reqId, "plan", planText));
            return "Plan submitted (request_id=" + reqId + "). Waiting for lead approval.";
        });

        // ---- 工作循环（should_exit 追踪） ----
        boolean shouldExit = false;

        for (int round = 0; round < 50; round++) {
            // 检查收件箱
            var inbox = bus.readInbox(name);
            for (var msg : inbox) {
                try {
                    paramsBuilder.addUserMessage(MAPPER.writeValueAsString(msg));
                } catch (IOException ignored) {}
            }

            // 如果上一轮已批准 shutdown，本轮读到 inbox 后退出
            if (shouldExit) break;

            try {
                Message response = client.messages().create(paramsBuilder.build());
                paramsBuilder.addMessage(response);

                if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                    break;
                }

                // 执行工具
                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (block.isToolUse()) {
                        ToolUseBlock toolUse = block.asToolUse();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> input = (Map<String, Object>)
                                AgentLoop.jsonValueToObject(toolUse._input());
                        String output = dispatcher.dispatch(toolUse.name(),
                                input != null ? input : Map.of());

                        System.out.println(Console.dim("  [" + name + "] "
                                + toolUse.name() + ": "
                                + output.substring(0, Math.min(120, output.length()))));

                        results.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId(toolUse.id())
                                        .content(output)
                                        .build()));

                        // 检查是否批准了 shutdown
                        if ("shutdown_response".equals(toolUse.name())
                                && input != null
                                && Boolean.TRUE.equals(input.get("approve"))) {
                            shouldExit = true;
                        }
                    }
                }
                paramsBuilder.addUserMessageOfBlockParams(results);
            } catch (Exception e) {
                System.out.println(Console.toolError("[" + name + "]", e.getMessage()));
                break;
            }
        }

        // 更新最终状态：shutdown（协议退出）或 idle（正常结束）
        setMemberStatus(name, shouldExit ? "shutdown" : "idle");
    }

    private static synchronized void setMemberStatus(String name, String status) {
        var member = findMember(name);
        if (member != null) {
            member.put("status", status);
            saveTeamConfig();
        }
    }

    // ==================== Lead 协议处理器 ====================

    /**
     * Lead 发起 shutdown 请求。
     * <p>
     * 对应 Python: handle_shutdown_request(teammate)
     */
    private static String handleShutdownRequest(String teammate) {
        String reqId = UUID.randomUUID().toString().substring(0, 8);
        shutdownRequests.put(reqId, new ConcurrentHashMap<>(Map.of(
                "target", teammate, "status", "pending")));
        bus.send("lead", teammate, "Please shut down gracefully.",
                "shutdown_request", Map.of("request_id", reqId));
        return "Shutdown request " + reqId + " sent to '" + teammate + "' (status: pending)";
    }

    /**
     * Lead 检查 shutdown 请求状态。
     * <p>
     * 对应 Python: _check_shutdown_status(request_id)
     */
    private static String checkShutdownStatus(String requestId) {
        var req = shutdownRequests.get(requestId);
        if (req == null) return "{\"error\": \"not found\"}";
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(req);
        } catch (IOException e) {
            return req.toString();
        }
    }

    /**
     * Lead 审批 teammate 的 plan。
     * <p>
     * 对应 Python: handle_plan_review(request_id, approve, feedback)
     */
    private static String handlePlanReview(String requestId, boolean approve, String feedback) {
        var req = planRequests.get(requestId);
        if (req == null) return "Error: Unknown plan request_id '" + requestId + "'";
        req.put("status", approve ? "approved" : "rejected");
        bus.send("lead", (String) req.get("from"),
                feedback != null ? feedback : "",
                "plan_approval_response",
                Map.of("request_id", requestId, "approve", approve,
                        "feedback", feedback != null ? feedback : ""));
        return "Plan " + req.get("status") + " for '" + req.get("from") + "'";
    }
}
