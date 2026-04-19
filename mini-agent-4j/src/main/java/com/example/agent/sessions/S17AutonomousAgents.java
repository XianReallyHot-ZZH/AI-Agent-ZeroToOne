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
import java.util.concurrent.*;

/**
 * S17：自治 Agent —— 持久化 Teammate 的 idle 轮询和任务认领（自包含实现）。
 * <p>
 * 在 S16 基础上增加 idle 轮询和任务认领，实现真正的自治 Teammate。
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
 * 核心洞察："自治 = 有工作就做，没工作就等。Lead 只管发任务，不管分配。"
 * <p>
 * 整个文件完全自包含。对应 Python 原版：s17_autonomous_agents.py
 */
public class S17AutonomousAgents {

    // ==================== 常量定义 ====================

    private static final Path WORKDIR = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    private static final long MAX_TOKENS = 8000;
    private static final int BASH_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT = 50000;
    /** IDLE 阶段轮询收件箱的间隔（秒） */
    private static final int POLL_INTERVAL_SECONDS = 5;
    /** IDLE 阶段超时时间（秒），超时后 teammate 自动 shutdown */
    private static final int IDLE_TIMEOUT_SECONDS = 60;
    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_CYAN = "\033[36m";
    private static final String ANSI_DIM = "\033[2m";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- 路径 ----
    private static final Path TEAM_DIR = WORKDIR.resolve(".team");
    private static final Path INBOX_DIR = TEAM_DIR.resolve("inbox");
    private static final Path TASKS_DIR = WORKDIR.resolve(".tasks");
    private static final Path CONFIG_PATH = TEAM_DIR.resolve("config.json");

    // ---- LLM（供 teammate 直接 API 调用） ----
    private static AnthropicClient client;
    private static String modelId;

    // ---- 协议状态 ----
    private static RequestStore requestStore;

    private static final Set<String> VALID_MSG_TYPES = Set.of(
            "message", "broadcast", "shutdown_request", "shutdown_response",
            "plan_approval", "plan_approval_response");

    /** 协议请求持久化存储（.team/requests/{request_id}.json）。 */
    public static class RequestStore {
        private final Path dir;
        private final Object lock = new Object();

        public RequestStore(Path baseDir) {
            this.dir = baseDir;
            try { Files.createDirectories(dir); } catch (IOException ignored) {}
        }

        private Path path(String requestId) { return dir.resolve(requestId + ".json"); }

        @SuppressWarnings("unchecked")
        public Map<String, Object> create(Map<String, Object> record) {
            String requestId = (String) record.get("request_id");
            synchronized (lock) {
                try { Files.writeString(path(requestId), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(record)); }
                catch (IOException e) { throw new RuntimeException(e); }
            }
            return record;
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> get(String requestId) {
            Path p = path(requestId);
            if (!Files.exists(p)) return null;
            try { return MAPPER.readValue(Files.readString(p), Map.class); }
            catch (IOException e) { return null; }
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> update(String requestId, Map<String, Object> changes) {
            synchronized (lock) {
                Map<String, Object> record = get(requestId);
                if (record == null) return null;
                record.putAll(changes);
                record.put("updated_at", System.currentTimeMillis() / 1000.0);
                try { Files.writeString(path(requestId), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(record)); }
                catch (IOException e) { throw new RuntimeException(e); }
                return record;
            }
        }
    }

    // ---- 团队状态 ----
    private static Map<String, Object> teamConfig;
    private static MessageBus bus;

    // ==================== 主入口 ====================

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().directory(WORKDIR.toString()).ignoreIfMissing().load();
        String baseUrl = dotenv.get("ANTHROPIC_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) System.clearProperty("ANTHROPIC_AUTH_TOKEN");
        String apiKey = dotenv.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("ANTHROPIC_API_KEY 未配置");
        modelId = dotenv.get("MODEL_ID");
        if (modelId == null || modelId.isBlank()) throw new IllegalStateException("MODEL_ID 未配置");

        client = buildClient(apiKey, baseUrl);
        bus = new MessageBus();
        requestStore = new RequestStore(TEAM_DIR.resolve("requests"));
        teamConfig = loadTeamConfig();
        try { Files.createDirectories(TASKS_DIR); } catch (IOException ignored) {}

        String systemPrompt = "You are a team lead at " + WORKDIR
                + ". Teammates are autonomous -- they find work themselves. "
                + "Create tasks with task_create, teammates will claim and execute them.";

        // ---- Lead 工具定义（14 个） ----
        List<Tool> tools = List.of(
                defineTool("bash", "Run a shell command.", Map.of("command", Map.of("type", "string")), List.of("command")),
                defineTool("read_file", "Read file contents.", Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer")), List.of("path")),
                defineTool("write_file", "Write content to file.", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("path", "content")),
                defineTool("edit_file", "Replace exact text.", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), List.of("path", "old_text", "new_text")),
                defineTool("spawn_teammate", "Spawn an autonomous teammate.", Map.of("name", Map.of("type", "string"), "role", Map.of("type", "string"), "prompt", Map.of("type", "string")), List.of("name", "role", "prompt")),
                defineTool("list_teammates", "List all teammates.", Map.of(), null),
                defineTool("send_message", "Send message to teammate.", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string"), "msg_type", Map.of("type", "string", "enum", List.of("message", "broadcast", "shutdown_request", "shutdown_response", "plan_approval", "plan_approval_response"))), List.of("to", "content")),
                defineTool("read_inbox", "Read lead inbox.", Map.of(), null),
                defineTool("broadcast", "Broadcast to all teammates.", Map.of("content", Map.of("type", "string")), List.of("content")),
                defineTool("shutdown_request", "Request teammate shutdown.", Map.of("teammate", Map.of("type", "string")), List.of("teammate")),
                defineTool("shutdown_response", "Check shutdown status.", Map.of("request_id", Map.of("type", "string")), List.of("request_id")),
                defineTool("plan_approval", "Approve/reject teammate plan.", Map.of("request_id", Map.of("type", "string"), "approve", Map.of("type", "boolean"), "feedback", Map.of("type", "string")), List.of("request_id", "approve")),
                defineTool("idle", "Lead does not idle.", Map.of(), null),
                defineTool("claim_task", "Claim a task.", Map.of("task_id", Map.of("type", "integer")), List.of("task_id"))
        );

        // ---- Lead 工具分发 ----
        Map<String, java.util.function.Function<Map<String, Object>, String>> handlers = new LinkedHashMap<>();
        handlers.put("bash", input -> runBash((String) input.get("command")));
        handlers.put("read_file", input -> runRead((String) input.get("path"), input.get("limit") instanceof Number n ? n.intValue() : null));
        handlers.put("write_file", input -> runWrite((String) input.get("path"), (String) input.get("content")));
        handlers.put("edit_file", input -> runEdit((String) input.get("path"), (String) input.get("old_text"), (String) input.get("new_text")));
        handlers.put("spawn_teammate", input -> spawnTeammate((String) input.get("name"), (String) input.get("role"), (String) input.get("prompt")));
        handlers.put("list_teammates", input -> listAll());
        handlers.put("send_message", input -> bus.send("lead", (String) input.get("to"), (String) input.get("content"), (String) input.getOrDefault("msg_type", "message"), null));
        handlers.put("read_inbox", input -> { try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(bus.readInbox("lead")); } catch (Exception e) { return "[]"; } });
        handlers.put("broadcast", input -> bus.broadcast("lead", (String) input.get("content"), memberNames()));
        handlers.put("shutdown_request", input -> handleShutdownRequest((String) input.get("teammate")));
        handlers.put("shutdown_response", input -> checkShutdownStatus((String) input.get("request_id")));
        handlers.put("plan_approval", input -> handlePlanReview((String) input.get("request_id"), Boolean.TRUE.equals(input.get("approve")), (String) input.get("feedback")));
        handlers.put("idle", input -> "Lead does not idle.");
        handlers.put("claim_task", input -> claimTask(((Number) input.get("task_id")).intValue(), "lead", null, "manual"));

        // ---- REPL ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(modelId).maxTokens(MAX_TOKENS).system(systemPrompt);
        for (Tool tool : tools) paramsBuilder.addTool(tool);

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print(ansiCyan("s17 >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            if ("/team".equals(query)) { System.out.println(listAll()); continue; }
            if ("/inbox".equals(query)) { try { System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(bus.readInbox("lead"))); } catch (Exception e) { System.out.println("[]"); } continue; }
            if ("/tasks".equals(query)) { System.out.println(listTasks()); continue; }

            paramsBuilder.addUserMessage(query);
            try { agentLoop(paramsBuilder, handlers); } catch (Exception e) { System.err.println("Error: " + e.getMessage()); }
            System.out.println();
        }
        System.out.println("Bye!");
    }

    // ==================== Agent 核心循环（带 inbox 注入） ====================

    private static void agentLoop(MessageCreateParams.Builder paramsBuilder,
                                  Map<String, java.util.function.Function<Map<String, Object>, String>> handlers) {
        while (true) {
            // 注入 lead inbox
            var inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                try {
                    paramsBuilder.addUserMessage("<inbox>" + MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(inbox) + "</inbox>");
                    paramsBuilder.addAssistantMessage("Noted inbox messages.");
                } catch (Exception ignored) {}
            }

            Message response = client.messages().create(paramsBuilder.build());
            paramsBuilder.addMessage(response);

            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                for (ContentBlock block : response.content()) block.text().ifPresent(tb -> System.out.println(tb.text()));
                return;
            }

            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (!block.isToolUse()) continue;
                ToolUseBlock tu = block.asToolUse();
                @SuppressWarnings("unchecked") Map<String, Object> input = (Map<String, Object>) jsonValueToObject(tu._input());
                if (input == null) input = Map.of();
                var handler = handlers.get(tu.name());
                String output;
                try { output = handler != null ? handler.apply(input) : "Unknown: " + tu.name(); }
                catch (Exception e) { output = "Error: " + e.getMessage(); }
                System.out.println(ANSI_DIM + "> " + tu.name() + ": " + output.substring(0, Math.min(200, output.length())) + ANSI_RESET);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(output).build()));
            }
            if (!results.isEmpty()) paramsBuilder.addUserMessageOfBlockParams(results);
        }
    }

    // ==================== MessageBus 内部类 ====================

    static class MessageBus {
        MessageBus() { try { Files.createDirectories(INBOX_DIR); } catch (IOException ignored) {} }

        String send(String sender, String to, String content, String msgType, Map<String, Object> extra) {
            if (!VALID_MSG_TYPES.contains(msgType))
                return "Error: Invalid type '" + msgType + "'. Valid: " + VALID_MSG_TYPES;
            var msg = new LinkedHashMap<String, Object>();
            msg.put("type", msgType); msg.put("from", sender); msg.put("content", content);
            msg.put("timestamp", System.currentTimeMillis() / 1000.0);
            if (extra != null) msg.putAll(extra);
            try (var writer = new FileWriter(INBOX_DIR.resolve(to + ".jsonl").toFile(), true)) {
                writer.write(MAPPER.writeValueAsString(msg) + "\n");
            } catch (IOException ignored) {}
            return "Sent " + msgType + " to " + to;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readInbox(String name) {
            Path p = INBOX_DIR.resolve(name + ".jsonl");
            if (!Files.exists(p)) return List.of();
            try {
                var lines = Files.readAllLines(p);
                var msgs = new ArrayList<Map<String, Object>>();
                for (String l : lines) { if (!l.isBlank()) try { msgs.add(MAPPER.readValue(l, Map.class)); } catch (Exception ignored) {} }
                Files.writeString(p, "");
                return msgs;
            } catch (IOException e) { return List.of(); }
        }

        String broadcast(String sender, String content, List<String> names) {
            int count = 0;
            for (String n : names) { if (!n.equals(sender)) { send(sender, n, content, "broadcast", null); count++; } }
            return "Broadcast to " + count + " teammates";
        }
    }

    // ==================== Teammate 管理 ====================

    @SuppressWarnings("unchecked")
    private static synchronized String spawnTeammate(String name, String role, String prompt) {
        var member = findMember(name);
        if (member != null) {
            String status = (String) member.get("status");
            if (!"idle".equals(status) && !"shutdown".equals(status)) return "Error: '" + name + "' is currently " + status;
            member.put("status", "working"); member.put("role", role);
        } else {
            member = new LinkedHashMap<>(Map.of("name", name, "role", role, "status", "working"));
            ((List<Map<String, Object>>) teamConfig.get("members")).add(member);
        }
        saveTeamConfig();
        Thread.ofVirtual().name("agent-" + name).start(() -> teammateLoop(name, role, prompt));
        return "Spawned '" + name + "' (role: " + role + ")";
    }

    /**
     * 自治 Teammate 工作循环：work → idle → work 两阶段生命周期。
     */
    @SuppressWarnings("unchecked")
    private static void teammateLoop(String name, String role, String prompt) {
        String teamName = (String) teamConfig.getOrDefault("team_name", "default");
        String sysPrompt = "You are '" + name + "', role: " + role + ", team: " + teamName + ", at " + WORKDIR
                + ". Use idle tool when done. You may auto-claim tasks.";

        var paramsBuilder = MessageCreateParams.builder().model(modelId).maxTokens(MAX_TOKENS).system(sysPrompt);

        // Teammate 工具集（10 个）
        List<Tool> tools = List.of(
                defineTool("bash", "Run command.", Map.of("command", Map.of("type", "string")), List.of("command")),
                defineTool("read_file", "Read file.", Map.of("path", Map.of("type", "string")), List.of("path")),
                defineTool("write_file", "Write file.", Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")), List.of("path", "content")),
                defineTool("edit_file", "Edit file.", Map.of("path", Map.of("type", "string"), "old_text", Map.of("type", "string"), "new_text", Map.of("type", "string")), List.of("path", "old_text", "new_text")),
                defineTool("send_message", "Send message.", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string"), "msg_type", Map.of("type", "string", "enum", List.of("message", "broadcast", "shutdown_request", "shutdown_response", "plan_approval", "plan_approval_response"))), List.of("to", "content")),
                defineTool("read_inbox", "Read inbox.", Map.of(), null),
                defineTool("shutdown_response", "Respond to shutdown.", Map.of("request_id", Map.of("type", "string"), "approve", Map.of("type", "boolean"), "reason", Map.of("type", "string")), List.of("request_id", "approve")),
                defineTool("plan_approval", "Submit plan.", Map.of("plan", Map.of("type", "string")), List.of("plan")),
                defineTool("idle", "Signal no more work.", Map.of(), null),
                defineTool("claim_task", "Claim task.", Map.of("task_id", Map.of("type", "integer")), List.of("task_id"))
        );
        for (Tool t : tools) paramsBuilder.addTool(t);
        paramsBuilder.addUserMessage(prompt);

        // Teammate 工具分发
        Map<String, java.util.function.Function<Map<String, Object>, String>> dispatch = new LinkedHashMap<>();
        dispatch.put("bash", input -> runBash((String) input.get("command")));
        dispatch.put("read_file", input -> runRead((String) input.get("path"), null));
        dispatch.put("write_file", input -> runWrite((String) input.get("path"), (String) input.get("content")));
        dispatch.put("edit_file", input -> runEdit((String) input.get("path"), (String) input.get("old_text"), (String) input.get("new_text")));
        dispatch.put("send_message", input -> bus.send(name, (String) input.get("to"), (String) input.get("content"), (String) input.getOrDefault("msg_type", "message"), null));
        dispatch.put("read_inbox", input -> { try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(bus.readInbox(name)); } catch (Exception e) { return "[]"; } });
        dispatch.put("shutdown_response", input -> {
            String reqId = (String) input.get("request_id");
            boolean approve = Boolean.TRUE.equals(input.get("approve"));
            String reason = (String) input.getOrDefault("reason", "");

            String status = approve ? "approved" : "rejected";
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("status", status);
            changes.put("resolved_by", name);
            changes.put("resolved_at", System.currentTimeMillis() / 1000.0);
            changes.put("response", Map.of("approve", approve, "reason", reason));
            var updated = requestStore.update(reqId, changes);
            if (updated == null) return "Error: Unknown shutdown request " + reqId;

            bus.send(name, "lead", reason, "shutdown_response", Map.of("request_id", reqId, "approve", approve));
            return "Shutdown " + (approve ? "approved" : "rejected");
        });
        dispatch.put("plan_approval", input -> {
            String planText = (String) input.getOrDefault("plan", "");
            String reqId = UUID.randomUUID().toString().substring(0, 8);
            double now = System.currentTimeMillis() / 1000.0;

            requestStore.create(new LinkedHashMap<>(Map.of(
                    "request_id", reqId, "kind", "plan_approval",
                    "from", name, "to", "lead", "status", "pending",
                    "plan", planText, "created_at", now, "updated_at", now)));

            bus.send(name, "lead", planText, "plan_approval",
                    Map.of("request_id", reqId, "plan", planText));
            return "Plan submitted (request_id=" + reqId + "). Waiting for approval.";
        });
        dispatch.put("claim_task", input -> claimTask(((Number) input.get("task_id")).intValue(), name, role, "manual"));
        // idle 在工作阶段内联拦截，不注册到分发

        // ==================== 持久化生命周期：work → idle → work ====================
        while (true) {
            // ---- WORK PHASE: 标准 agent 循环，最多 50 轮 ----
            boolean idleRequested = false;

            for (int round = 0; round < 50; round++) {
                var inbox = bus.readInbox(name);
                for (var msg : inbox) {
                    if ("shutdown_request".equals(msg.get("type"))) { setMemberStatus(name, "shutdown"); return; }
                    try { paramsBuilder.addUserMessage(MAPPER.writeValueAsString(msg)); } catch (Exception ignored) {}
                }

                try {
                    Message resp = client.messages().create(paramsBuilder.build());
                    paramsBuilder.addMessage(resp);
                    if (!resp.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) break;

                    List<ContentBlockParam> results = new ArrayList<>();
                    for (ContentBlock block : resp.content()) {
                        if (!block.isToolUse()) continue;
                        ToolUseBlock tu = block.asToolUse();

                        // 内联拦截 idle → 触发 idle phase
                        if ("idle".equals(tu.name())) {
                            idleRequested = true;
                            String output = "Entering idle phase.";
                            System.out.println(ANSI_DIM + "  [" + name + "] idle: " + output + ANSI_RESET);
                            results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(output).build()));
                            continue;
                        }

                        @SuppressWarnings("unchecked") Map<String, Object> input = (Map<String, Object>) jsonValueToObject(tu._input());
                        if (input == null) input = Map.of();
                        var h = dispatch.get(tu.name());
                        String output;
                        try { output = h != null ? h.apply(input) : "Unknown"; } catch (Exception e) { output = "Error: " + e.getMessage(); }
                        System.out.println(ANSI_DIM + "  [" + name + "] " + tu.name() + ": " + output.substring(0, Math.min(120, output.length())) + ANSI_RESET);
                        results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(output).build()));
                    }
                    paramsBuilder.addUserMessageOfBlockParams(results);
                    if (idleRequested) break;
                } catch (Exception e) {
                    System.out.println(ANSI_DIM + "  [" + name + "] error: " + e.getMessage() + ANSI_RESET);
                    setMemberStatus(name, "idle"); return;
                }
            }

            // ---- IDLE PHASE: 轮询收件箱和未认领任务 ----
            setMemberStatus(name, "idle");
            boolean resume = false;
            int polls = IDLE_TIMEOUT_SECONDS / Math.max(POLL_INTERVAL_SECONDS, 1);

            for (int p = 0; p < polls; p++) {
                try { Thread.sleep(POLL_INTERVAL_SECONDS * 1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); setMemberStatus(name, "shutdown"); return; }

                // 检查收件箱
                var inbox = bus.readInbox(name);
                if (!inbox.isEmpty()) {
                    // 身份再注入
                    paramsBuilder.addUserMessage("<identity>You are '" + name + "', role: " + role + ", team: " + teamName + ".</identity>");
                    paramsBuilder.addAssistantMessage("I am " + name + ". Continuing.");
                    for (var msg : inbox) {
                        if ("shutdown_request".equals(msg.get("type"))) { setMemberStatus(name, "shutdown"); return; }
                        try { paramsBuilder.addUserMessage(MAPPER.writeValueAsString(msg)); } catch (Exception ignored) {}
                    }
                    resume = true; break;
                }

                // 扫描未认领任务（按角色过滤）
                // 角色过滤是 S17 的关键机制：任务可以通过 required_role 字段指定
                // 只有特定角色的 teammate 才能认领。例如 "required_role": "coder"
                // 的任务只会被 role="coder" 的 teammate 自动认领。
                // 如果任务没有设置 required_role，则所有角色都可以认领。
                var unclaimed = scanUnclaimedTasks(role);
                if (!unclaimed.isEmpty()) {
                    var task = unclaimed.get(0);
                    int taskId = ((Number) task.get("id")).intValue();
                    String claimResult = claimTask(taskId, name, role, "auto");
                    if (claimResult.startsWith("Error:")) continue;

                    // 身份再注入
                    paramsBuilder.addUserMessage("<identity>You are '" + name + "', role: " + role + ", team: " + teamName + ".</identity>");
                    paramsBuilder.addAssistantMessage("I am " + name + ". Continuing.");
                    paramsBuilder.addUserMessage("<auto-claimed>Task #" + task.get("id") + ": " + task.get("subject") + "\n" + task.getOrDefault("description", "") + "</auto-claimed>");
                    paramsBuilder.addAssistantMessage(claimResult + ". Working on it.");
                    resume = true; break;
                }
            }

            if (!resume) { setMemberStatus(name, "shutdown"); return; }
            setMemberStatus(name, "working");
        }
    }

    // ==================== Task 管理 ====================

    private static int maxTaskId() {
        try (var s = Files.list(TASKS_DIR)) {
            return s.filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .mapToInt(p -> { String n = p.getFileName().toString(); return Integer.parseInt(n.substring(5, n.length()-5)); })
                    .max().orElse(0);
        } catch (IOException e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static String listTasks() {
        try (var s = Files.list(TASKS_DIR)) {
            var tasks = s.filter(p -> p.getFileName().toString().matches("task_\\d+\\.json")).sorted()
                    .map(p -> { try { return (Map<String,Object>)MAPPER.readValue(Files.readString(p), Map.class); } catch (Exception e) { return null; } })
                    .filter(Objects::nonNull).toList();
            if (tasks.isEmpty()) return "No tasks.";
            var lines = new ArrayList<String>();
            for (var t : tasks) {
                String m = switch((String)t.getOrDefault("status","?")) { case "pending"->"[ ]"; case "in_progress"->"[>]"; case "completed"->"[x]"; default->"[?]"; };
                String owner = t.get("owner")!=null && !t.get("owner").toString().isEmpty() ? " @"+t.get("owner") : "";
                lines.add(m+" #"+t.get("id")+": "+t.get("subject")+owner);
            }
            return String.join("\n", lines);
        } catch (IOException e) { return "Error: "+e.getMessage(); }
    }

    /**
     * 扫描未认领的任务，按角色过滤。
     * <p>
     * 过滤条件（全部满足才会被返回）：
     * 1. status == "pending"：只看未开始的任务
     * 2. owner 为空：尚未被任何 teammate 认领
     * 3. blockedBy 为空：没有未完成的依赖（有依赖的任务不能开始）
     * 4. required_role 匹配：如果任务指定了 required_role，
     *    则只返回给角色匹配的 teammate（实现任务→角色的精准分配）
     *
     * @param role 当前 teammate 的角色名称
     * @return 可认领的任务列表（按 ID 排序）
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> scanUnclaimedTasks(String role) {
        try (var s = Files.list(TASKS_DIR)) {
            var result = new ArrayList<Map<String, Object>>();
            for (var p : s.filter(p -> p.getFileName().toString().matches("task_\\d+\\.json")).sorted().toList()) {
                try {
                    var t = (Map<String, Object>) MAPPER.readValue(Files.readString(p), Map.class);
                    if (!"pending".equals(t.get("status"))) continue;
                    if (t.get("owner") != null && !t.get("owner").toString().isEmpty()) continue;
                    if (!((List<?>) t.getOrDefault("blockedBy", List.of())).isEmpty()) continue;
                    // 角色过滤：支持 claim_role 和 required_role 两种字段名
                    String requiredRole = "";
                    Object cr = t.get("claim_role"), rr = t.get("required_role");
                    if (cr != null && !cr.toString().isEmpty()) requiredRole = cr.toString();
                    else if (rr != null && !rr.toString().isEmpty()) requiredRole = rr.toString();
                    if (!requiredRole.isEmpty() && !requiredRole.equals(role)) continue;
                    result.add(t);
                } catch (Exception ignored) {}
            }
            return result;
        } catch (IOException e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private static synchronized String claimTask(int taskId, String owner, String role, String source) {
        Path p = TASKS_DIR.resolve("task_" + taskId + ".json");
        if (!Files.exists(p)) return "Error: Task " + taskId + " not found";
        try {
            var t = MAPPER.readValue(Files.readString(p), Map.class);
            // 角色验证
            if (!"pending".equals(t.get("status"))) return "Error: Task " + taskId + " is not claimable for role=" + (role != null ? role : "(any)");
            if (t.get("owner") != null && !t.get("owner").toString().isEmpty()) return "Error: Task " + taskId + " is not claimable for role=" + (role != null ? role : "(any)");
            String requiredRole = "";
            Object cr = t.get("claim_role"), rr = t.get("required_role");
            if (cr != null && !cr.toString().isEmpty()) requiredRole = cr.toString();
            else if (rr != null && !rr.toString().isEmpty()) requiredRole = rr.toString();
            if (!requiredRole.isEmpty() && !requiredRole.equals(role != null ? role : ""))
                return "Error: Task " + taskId + " is not claimable for role=" + (role != null ? role : "(any)");

            t.put("owner", owner);
            t.put("status", "in_progress");
            t.put("claimed_at", System.currentTimeMillis() / 1000.0);
            t.put("claim_source", source != null ? source : "manual");
            Files.writeString(p, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(t));

            // 写入 claim 事件日志
            Path claimEvents = TASKS_DIR.resolve("claim_events.jsonl");
            var event = new LinkedHashMap<String, Object>();
            event.put("event", "task.claimed");
            event.put("task_id", taskId);
            event.put("owner", owner);
            event.put("role", role);
            event.put("source", source);
            event.put("ts", System.currentTimeMillis() / 1000.0);
            Files.writeString(claimEvents, MAPPER.writeValueAsString(event) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            return "Claimed task #" + taskId + " for " + owner + " via " + (source != null ? source : "manual");
        } catch (IOException e) { return "Error: " + e.getMessage(); }
    }

    // ==================== 团队配置 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadTeamConfig() {
        try { Files.createDirectories(TEAM_DIR); } catch (IOException ignored) {}
        if (Files.exists(CONFIG_PATH)) { try { return MAPPER.readValue(Files.readString(CONFIG_PATH), Map.class); } catch (IOException ignored) {} }
        var cfg = new LinkedHashMap<String, Object>(); cfg.put("team_name", "default"); cfg.put("members", new ArrayList<>()); return cfg;
    }

    private static synchronized void saveTeamConfig() {
        try { Files.writeString(CONFIG_PATH, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(teamConfig)); } catch (IOException ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static synchronized Map<String, Object> findMember(String name) {
        return ((List<Map<String, Object>>) teamConfig.get("members")).stream().filter(m -> name.equals(m.get("name"))).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static String listAll() {
        var members = (List<Map<String, Object>>) teamConfig.get("members");
        if (members.isEmpty()) return "No teammates.";
        var lines = new ArrayList<String>(); lines.add("Team: " + teamConfig.get("team_name"));
        for (var m : members) lines.add("  " + m.get("name") + " (" + m.get("role") + "): " + m.get("status"));
        return String.join("\n", lines);
    }

    @SuppressWarnings("unchecked")
    private static List<String> memberNames() {
        return ((List<Map<String, Object>>) teamConfig.get("members")).stream().map(m -> (String) m.get("name")).toList();
    }

    private static synchronized void setMemberStatus(String name, String status) {
        var m = findMember(name); if (m != null) { m.put("status", status); saveTeamConfig(); }
    }

    // ==================== 协议处理器 ====================

    private static String handleShutdownRequest(String teammate) {
        String reqId = UUID.randomUUID().toString().substring(0, 8);
        double now = System.currentTimeMillis() / 1000.0;
        requestStore.create(new LinkedHashMap<>(Map.of(
                "request_id", reqId, "kind", "shutdown",
                "from", "lead", "to", teammate, "status", "pending",
                "created_at", now, "updated_at", now)));
        bus.send("lead", teammate, "Please shut down gracefully.", "shutdown_request", Map.of("request_id", reqId));
        return "Shutdown request " + reqId + " sent to '" + teammate + "'";
    }

    private static String checkShutdownStatus(String reqId) {
        var req = requestStore.get(reqId);
        if (req == null) return "{\"error\": \"not found\"}";
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(req); } catch (Exception e) { return req.toString(); }
    }

    private static String handlePlanReview(String reqId, boolean approve, String feedback) {
        var req = requestStore.get(reqId);
        if (req == null) return "Error: Unknown plan request_id '" + reqId + "'";
        String status = approve ? "approved" : "rejected";
        String fb = feedback != null ? feedback : "";
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("status", status);
        changes.put("reviewed_by", "lead");
        changes.put("resolved_at", System.currentTimeMillis() / 1000.0);
        changes.put("feedback", fb);
        requestStore.update(reqId, changes);
        bus.send("lead", (String) req.get("from"), fb, "plan_approval_response",
                Map.of("request_id", reqId, "approve", approve, "feedback", fb));
        return "Plan " + status + " for '" + req.get("from") + "'";
    }

    // ==================== 基础设施 ====================

    private static AnthropicClient buildClient(String apiKey, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) return AnthropicOkHttpClient.builder().apiKey(apiKey).baseUrl(baseUrl).build();
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    private static Tool defineTool(String name, String desc, Map<String,Object> props, List<String> req) {
        var sb = Tool.InputSchema.builder().properties(JsonValue.from(props));
        if (req != null && !req.isEmpty()) sb.putAdditionalProperty("required", JsonValue.from(req));
        return Tool.builder().name(name).description(desc).inputSchema(sb.build()).build();
    }

    private static Path safePath(String p) { Path r = WORKDIR.resolve(p).normalize().toAbsolutePath(); if (!r.startsWith(WORKDIR)) throw new IllegalArgumentException("Path escapes workspace: "+p); return r; }

    private static String runBash(String cmd) {
        if (cmd==null||cmd.isBlank()) return "Error: command required";
        for (String d : List.of("rm -rf /","sudo","shutdown","reboot","> /dev/")) if (cmd.contains(d)) return "Error: Dangerous command blocked";
        try { ProcessBuilder pb = System.getProperty("os.name").toLowerCase().contains("win") ? new ProcessBuilder("cmd","/c",cmd) : new ProcessBuilder("bash","-c",cmd);
            pb.directory(WORKDIR.toFile()).redirectErrorStream(true); Process p = pb.start();
            String o = new String(p.getInputStream().readAllBytes()).trim(); if (!p.waitFor(BASH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { p.destroyForcibly(); return "Error: Timeout"; }
            if (o.isEmpty()) return "(no output)"; return o.length()>MAX_OUTPUT ? o.substring(0,MAX_OUTPUT) : o;
        } catch (Exception e) { return "Error: "+e.getMessage(); }
    }

    private static String runRead(String path, Integer limit) {
        try { var lines = Files.readAllLines(safePath(path)); if (limit!=null && limit<lines.size()) { lines = new ArrayList<>(lines.subList(0,limit)); lines.add("... ("+(lines.size()-limit)+" more)"); }
            String r = String.join("\n",lines); return r.length()>MAX_OUTPUT ? r.substring(0,MAX_OUTPUT) : r;
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
