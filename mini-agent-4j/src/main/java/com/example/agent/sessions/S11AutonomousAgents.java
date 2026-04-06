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
 * S11：自治 Agent —— 自包含实现（不依赖 TeamManager / TeamProtocol）。
 * <p>
 * 在 S10 基础上增加 idle 轮询和任务认领，实现真正的自治 Teammate。
 * <p>
 * Teammate 两阶段生命周期：
 * <pre>
 *   while True:
 *     WORK PHASE:  agent loop (50 rounds), intercept idle → break to idle
 *     IDLE PHASE:  poll every 5s for up to 60s
 *       ├─ inbox message found → resume WORK
 *       ├─ unclaimed task found → auto-claim → resume WORK
 *       └─ timeout → shutdown
 * </pre>
 * <p>
 * 关键洞察："自治 = 有工作就做，没工作就等。Lead 只管发任务，不管分配。"
 * <p>
 * REPL 命令：/team, /inbox, /tasks
 * <p>
 * 对应 Python 原版：s11_autonomous_agents.py
 */
public class S11AutonomousAgents {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int POLL_INTERVAL_SECONDS = 5;
    private static final int IDLE_TIMEOUT_SECONDS = 60;

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

    // ---- Task state ----
    private static Path tasksDir;

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
        tasksDir = workDir.resolve(".tasks");
        try {
            Files.createDirectories(tasksDir);
        } catch (IOException ignored) {}

        String systemPrompt = "You are a team lead at " + workDir
                + ". Teammates are autonomous -- they find work themselves. "
                + "Create tasks with task_create, teammates will claim and execute them.";

        // ---- Lead 工具定义（14 个） ----
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
                        "Request a teammate to shut down gracefully.",
                        Map.of("teammate", Map.of("type", "string")),
                        List.of("teammate")),
                AgentLoop.defineTool("shutdown_response",
                        "Check shutdown request status by request_id.",
                        Map.of("request_id", Map.of("type", "string")),
                        List.of("request_id")),
                AgentLoop.defineTool("plan_approval",
                        "Approve or reject a teammate's plan by request_id.",
                        Map.of("request_id", Map.of("type", "string"),
                                "approve", Map.of("type", "boolean"),
                                "feedback", Map.of("type", "string")),
                        List.of("request_id", "approve")),
                // ---- Lead 也有 idle 和 claim_task（虽然 rarely used） ----
                AgentLoop.defineTool("idle",
                        "Enter idle state (for lead -- rarely used).",
                        Map.of(), null),
                AgentLoop.defineTool("claim_task",
                        "Claim a task from the task board by ID.",
                        Map.of("task_id", Map.of("type", "integer")),
                        List.of("task_id"))
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
        // ---- Lead 的 idle 和 claim_task ----
        dispatcher.register("idle", input -> "Lead does not idle.");
        dispatcher.register("claim_task", input ->
                claimTask(((Number) input.get("task_id")).intValue(), "lead"));

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
            System.out.print(Console.cyan("s11 >> "));
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
            if ("/tasks".equals(query)) {
                System.out.println(listTasks());
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

    // ==================== Task 管理（自包含） ====================

    /**
     * 列出所有任务（REPL /tasks 用）。
     */
    @SuppressWarnings("unchecked")
    private static String listTasks() {
        try (var stream = Files.list(tasksDir)) {
            var tasks = stream
                    .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .sorted()
                    .map(p -> {
                        try {
                            return MAPPER.readValue(Files.readString(p), Map.class);
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
                        ? " @" + t.get("owner") : "";
                lines.add("  " + marker + " #" + t.get("id") + ": " + t.get("subject") + owner);
            }
            return String.join("\n", lines);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 创建任务。
     */
    @SuppressWarnings("unchecked")
    private static synchronized String createTask(String subject, String description) {
        int nextId = maxTaskId() + 1;
        var task = new LinkedHashMap<String, Object>();
        task.put("id", nextId);
        task.put("subject", subject);
        task.put("description", description != null ? description : "");
        task.put("status", "pending");
        task.put("owner", "");
        task.put("blockedBy", new ArrayList<Integer>());
        task.put("blocks", new ArrayList<Integer>());
        try {
            Files.writeString(tasksDir.resolve("task_" + nextId + ".json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task));
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (IOException e) {
            return task.toString();
        }
    }

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

    /**
     * 扫描未认领的任务（idle phase 用）。
     * <p>
     * 对应 Python: scan_unclaimed_tasks()
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> scanUnclaimedTasks() {
        try (var stream = Files.list(tasksDir)) {
            var result = new ArrayList<Map<String, Object>>();
            var paths = stream
                    .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .sorted()
                    .toList();
            for (var path : paths) {
                try {
                    var task = (Map<String, Object>) MAPPER.readValue(Files.readString(path), Map.class);
                    String status = (String) task.getOrDefault("status", "");
                    String owner = (String) task.getOrDefault("owner", "");
                    List<Integer> blockedBy = (List<Integer>) task.getOrDefault("blockedBy", List.of());
                    if ("pending".equals(status)
                            && (owner == null || owner.isEmpty())
                            && blockedBy.isEmpty()) {
                        result.add(task);
                    }
                } catch (IOException ignored) {}
            }
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 认领任务（线程安全）。
     * <p>
     * 对应 Python: claim_task(task_id, owner)
     */
    @SuppressWarnings("unchecked")
    private static synchronized String claimTask(int taskId, String owner) {
        Path path = tasksDir.resolve("task_" + taskId + ".json");
        if (!Files.exists(path)) {
            return "Error: Task " + taskId + " not found";
        }
        try {
            var task = MAPPER.readValue(Files.readString(path), Map.class);
            task.put("owner", owner);
            task.put("status", "in_progress");
            Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task));
            return "Claimed task #" + taskId + " for " + owner;
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
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
     * 自治 Teammate 工作循环：持久化生命周期（work → idle → work）。
     * <p>
     * 对应 Python: TeammateManager._loop(name, role, prompt)
     * <ul>
     *   <li>WORK PHASE: agent loop，内联拦截 idle 工具 → 进入 idle phase</li>
     *   <li>IDLE PHASE: 每 5s 轮询收件箱和未认领任务，最长 60s</li>
     *   <li>shutdown_request 在代码级检查（work 和 idle 阶段均检查）</li>
     *   <li>超时无任务 → shutdown，有任务/消息 → resume WORK</li>
     * </ul>
     */
    private static void teammateLoop(String name, String role, String prompt) {
        String teamName = (String) teamConfig.getOrDefault("team_name", "default");
        String sysPrompt = "You are '" + name + "', role: " + role + ", team: " + teamName
                + ", at " + workDir + ". "
                + "Use idle tool when you have no more work. You will auto-claim new tasks.";

        var paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000)
                .system(sysPrompt);

        // Teammate 工具集（10 个）：6 base + shutdown_response + plan_approval + idle + claim_task
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
                        List.of("plan")),
                // ---- 自治工具 ----
                AgentLoop.defineTool("idle",
                        "Signal that you have no more work. Enters idle polling phase.",
                        Map.of(), null),
                AgentLoop.defineTool("claim_task",
                        "Claim a task from the task board by ID.",
                        Map.of("task_id", Map.of("type", "integer")),
                        List.of("task_id"))
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
            var req = shutdownRequests.get(reqId);
            if (req != null) {
                req.put("status", approve ? "approved" : "rejected");
            }
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
            return "Plan submitted (request_id=" + reqId + "). Waiting for approval.";
        });
        // ---- 自治工具分发 ----
        // idle 不注册到 dispatcher —— 在 work phase 内联拦截
        dispatcher.register("claim_task", input ->
                claimTask(((Number) input.get("task_id")).intValue(), name));

        // ==================== 持久化生命周期：work → idle → work ====================
        while (true) {
            // ---- WORK PHASE: 标准 agent loop，最多 50 轮 ----
            boolean idleRequested = false;

            for (int round = 0; round < 50; round++) {
                // 检查收件箱（代码级 shutdown_request 检查）
                var inbox = bus.readInbox(name);
                for (var msg : inbox) {
                    if ("shutdown_request".equals(msg.get("type"))) {
                        setMemberStatus(name, "shutdown");
                        return;
                    }
                    try {
                        paramsBuilder.addUserMessage(MAPPER.writeValueAsString(msg));
                    } catch (IOException ignored) {}
                }

                try {
                    Message response = client.messages().create(paramsBuilder.build());
                    paramsBuilder.addMessage(response);

                    if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                        // LLM 决定停止 → 进入 idle phase
                        break;
                    }

                    // 执行工具（内联拦截 idle）
                    List<ContentBlockParam> results = new ArrayList<>();
                    for (ContentBlock block : response.content()) {
                        if (block.isToolUse()) {
                            ToolUseBlock toolUse = block.asToolUse();

                            // 内联拦截 idle 工具 → 触发 idle phase
                            if ("idle".equals(toolUse.name())) {
                                idleRequested = true;
                                String output = "Entering idle phase. Will poll for new tasks.";
                                System.out.println(Console.dim("  [" + name + "] idle: " + output));
                                results.add(ContentBlockParam.ofToolResult(
                                        ToolResultBlockParam.builder()
                                                .toolUseId(toolUse.id())
                                                .content(output)
                                                .build()));
                                continue;
                            }

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
                        }
                    }
                    paramsBuilder.addUserMessageOfBlockParams(results);

                    if (idleRequested) break;
                } catch (Exception e) {
                    System.out.println(Console.toolError("[" + name + "]", e.getMessage()));
                    setMemberStatus(name, "idle");
                    return;
                }
            }

            // ---- IDLE PHASE: 轮询收件箱和未认领任务 ----
            setMemberStatus(name, "idle");
            boolean resume = false;
            int polls = IDLE_TIMEOUT_SECONDS / Math.max(POLL_INTERVAL_SECONDS, 1);

            for (int p = 0; p < polls; p++) {
                try {
                    Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    setMemberStatus(name, "shutdown");
                    return;
                }

                // 检查收件箱
                var inbox = bus.readInbox(name);
                if (!inbox.isEmpty()) {
                    for (var msg : inbox) {
                        // 代码级 shutdown_request 检查
                        if ("shutdown_request".equals(msg.get("type"))) {
                            setMemberStatus(name, "shutdown");
                            return;
                        }
                        try {
                            paramsBuilder.addUserMessage(MAPPER.writeValueAsString(msg));
                        } catch (IOException ignored) {}
                    }
                    resume = true;
                    break;
                }

                // 扫描未认领任务
                var unclaimed = scanUnclaimedTasks();
                if (!unclaimed.isEmpty()) {
                    var task = unclaimed.get(0);
                    int taskId = ((Number) task.get("id")).intValue();
                    claimTask(taskId, name);

                    // 身份再注入（上下文压缩后恢复）
                    injectIdentityIfNeeded(paramsBuilder, name, role, teamName);

                    String taskPrompt = "<auto-claimed>Task #" + task.get("id")
                            + ": " + task.get("subject") + "\n"
                            + task.getOrDefault("description", "") + "</auto-claimed>";
                    try {
                        paramsBuilder.addUserMessage(taskPrompt);
                        paramsBuilder.addAssistantMessage("Claimed task #" + taskId + ". Working on it.");
                    } catch (Exception ignored) {}

                    resume = true;
                    break;
                }
            }

            if (!resume) {
                // 超时无任务 → shutdown
                setMemberStatus(name, "shutdown");
                return;
            }

            // resume → 回到 WORK PHASE
            setMemberStatus(name, "working");
        }
    }

    /**
     * 身份再注入：上下文压缩后恢复 teammate 身份信息。
     * <p>
     * 对应 Python: make_identity_block(name, role, team_name)
     * 当消息历史较短（≤3 条 user 消息）时注入身份块。
     */
    private static void injectIdentityIfNeeded(
            MessageCreateParams.Builder paramsBuilder, String name, String role, String teamName) {
        // 简化检查：始终在 auto-claim 后注入身份信息
        // Python 检查 len(messages) <= 3，Java 的 paramsBuilder 无法精确计数
        paramsBuilder.addUserMessage(
                "<identity>You are '" + name + "', role: " + role + ", team: " + teamName
                        + ". Continue your work.</identity>");
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
        return "Shutdown request " + reqId + " sent to '" + teammate + "'";
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
