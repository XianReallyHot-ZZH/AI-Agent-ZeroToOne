package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.*;
import com.example.agent.background.BackgroundManager;
import com.example.agent.compress.ContextCompressor;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.skills.SkillLoader;
import com.example.agent.tasks.TodoManager;
import com.example.agent.team.MessageBus;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.Console;
import com.example.agent.util.EnvLoader;
import com.example.agent.util.PathSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SFull：全量参考实现 —— 所有机制的完整集成（自包含）。
 * <p>
 * 整合 s01-s11 全部机制的自包含实现（不依赖 TeamManager / TeamProtocol / TaskManager）：
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │                        FULL AGENT                            │
 * │                                                              │
 * │  System prompt (s05 skills, task-first + optional todo nag) │
 * │                                                              │
 * │  Before each LLM call:                                       │
 * │  ┌──────────────────┐ ┌────────────────┐ ┌──────────────┐  │
 * │  │ Auto-compact(s06)│ │ Drain bg (s08) │ │ Check inbox  │  │
 * │  │ Token threshold  │ │ notifications  │ │ (s09)        │  │
 * │  └──────────────────┘ └────────────────┘ └──────────────┘  │
 * │                                                              │
 * │  Tool dispatch (s02 pattern): 23 个工具                      │
 * │  After tool execution: Nag reminder (s03) if needed         │
 * │  Manual compress (s06): replaces message history             │
 * │                                                              │
 * │  Teammate (s11): persistent lifecycle, auto-claim tasks      │
 * │  Protocols (s10): shutdown_request + plan_approval           │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * REPL 命令：/compact /tasks /team /inbox
 * <p>
 * 对应 Python 原版：s_full.py（~737 行）
 */
public class SFullAgent {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- Constants ----
    private static final long TOKEN_THRESHOLD = 100000;
    private static final int POLL_INTERVAL_SECONDS = 5;
    private static final int IDLE_TIMEOUT_SECONDS = 60;

    // ---- Shared modules ----
    private static Path workDir;
    private static PathSandbox sandbox;
    private static TodoManager todo;
    private static SkillLoader skills;
    private static BackgroundManager bg;
    private static MessageBus bus;
    private static AnthropicClient client;
    private static String model;

    // ---- Self-contained state ----
    // Task management
    private static Path tasksDir;
    private static int nextTaskId;

    // Team config
    private static Path teamDir;
    private static Path configPath;
    private static Map<String, Object> teamConfig;

    // Protocol trackers
    private static final ConcurrentHashMap<String, Map<String, Object>> shutdownRequests = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Map<String, Object>> planRequests = new ConcurrentHashMap<>();

    // Compression tracking
    private static long tokenEstimate = 0;
    private static final List<String> conversationLog = new ArrayList<>();

    // Main loop state
    private static MessageCreateParams.Builder paramsBuilder;
    private static String systemPrompt;
    private static List<Tool> fullTools;
    private static ToolDispatcher dispatcher;

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        // ==================== 初始化 ====================
        workDir = Path.of(System.getProperty("user.dir"));
        sandbox = new PathSandbox(workDir);
        todo = new TodoManager();
        skills = new SkillLoader(workDir.resolve("skills"));
        bg = new BackgroundManager(workDir);
        tasksDir = workDir.resolve(".tasks");
        teamDir = workDir.resolve(".team");
        configPath = teamDir.resolve("config.json");
        bus = new MessageBus(teamDir.resolve("inbox"));
        teamConfig = loadTeamConfig();

        try { Files.createDirectories(tasksDir); } catch (IOException ignored) {}
        nextTaskId = maxTaskId() + 1;

        client = buildClient();
        model = EnvLoader.getModelId();

        systemPrompt = "You are a coding agent at " + workDir + ". Use tools to solve tasks.\n"
                + "Prefer task_create/task_update/task_list for multi-step work. "
                + "Use TodoWrite for short checklists.\n"
                + "Use task for subagent delegation. Use load_skill for specialized knowledge.\n"
                + "Skills:\n" + skills.getDescriptions();

        // ==================== 工具定义（23 个） ====================
        fullTools = buildFullToolList();

        // ==================== 工具分发器 ====================
        dispatcher = new ToolDispatcher();
        registerDispatchers();

        // ==================== 构建参数 ====================
        paramsBuilder = MessageCreateParams.builder()
                .model(model).maxTokens(8000).system(systemPrompt);
        for (Tool t : fullTools) paramsBuilder.addTool(t);

        // ==================== REPL ====================
        Scanner scanner = new Scanner(System.in);
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   mini-agent-4j SFullAgent (Java 21)    ║");
        System.out.println("║   23 tools | /compact /tasks /team /inbox║");
        System.out.println("╚══════════════════════════════════════════╝");

        while (true) {
            System.out.print(Console.cyan("s_full >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            switch (query) {
                case "/compact" -> {
                    System.out.println("[manual compact via /compact]");
                    doAutoCompact();
                    continue;
                }
                case "/tasks" -> {
                    System.out.println(listTasks());
                    continue;
                }
                case "/team" -> {
                    System.out.println(listAll());
                    continue;
                }
                case "/inbox" -> {
                    try {
                        var msgs = bus.readInbox("lead");
                        System.out.println(msgs.isEmpty() ? "[]"
                                : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(msgs));
                    } catch (Exception e) { System.out.println("[]"); }
                    continue;
                }
                default -> {}
            }

            // Add user message
            paramsBuilder.addUserMessage(query);
            tokenEstimate += query.length() / 4;
            conversationLog.add("user: " + query);

            // Run custom agent loop
            try {
                fullAgentLoop();
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
    }

    // ==================== Custom Agent Loop ====================

    /**
     * 完整 Agent 循环：每轮 LLM 调用前执行 bg drain + inbox check + auto-compact。
     * 工具执行后检查 nag reminder。手动 compress 触发时替换消息历史。
     */
    private static void fullAgentLoop() {
        int roundsWithoutTodo = 0;

        while (true) {
            // ---- 1. Auto-compact threshold check ----
            if (tokenEstimate > TOKEN_THRESHOLD) {
                doAutoCompact();
            }

            // ---- 2. Drain background notifications ----
            var notifs = bg.drainNotifications();
            if (!notifs.isEmpty()) {
                var sb = new StringBuilder("<background-results>\n");
                for (var n : notifs) {
                    sb.append("[bg:").append(n.get("task_id")).append("] ")
                            .append(n.get("status")).append(": ").append(n.get("result")).append("\n");
                }
                sb.append("</background-results>");
                paramsBuilder.addUserMessage(sb.toString());
                System.out.println(Console.yellow(sb.toString().trim()));
            }

            // ---- 3. Drain lead inbox ----
            var inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                try {
                    paramsBuilder.addUserMessage("<inbox>" + MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(inbox) + "</inbox>");
                } catch (Exception ignored) {}
            }

            // ---- 4. LLM call ----
            Message response = client.messages().create(paramsBuilder.build());
            paramsBuilder.addMessage(response);

            // Track response tokens
            for (ContentBlock block : response.content()) {
                block.text().ifPresent(tb -> {
                    tokenEstimate += tb.text().length() / 4;
                    conversationLog.add("assistant: " + tb.text());
                });
            }

            // ---- 5. Check stop reason ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(tb -> System.out.println(tb.text()));
                }
                return;
            }

            // ---- 6. Tool execution ----
            List<ContentBlockParam> results = new ArrayList<>();
            boolean usedTodo = false;
            boolean manualCompress = false;

            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    String toolName = toolUse.name();

                    if ("compress".equals(toolName)) manualCompress = true;
                    if ("TodoWrite".equals(toolName)) usedTodo = true;

                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>)
                            AgentLoop.jsonValueToObject(toolUse._input());
                    String output;
                    try {
                        output = dispatcher.dispatch(toolName, input != null ? input : Map.of());
                    } catch (Exception e) {
                        output = "Error: " + e.getMessage();
                    }

                    System.out.println("> " + toolName + ": " + output.substring(0, Math.min(200, output.length())));

                    if (output.length() > 50000) output = output.substring(0, 50000);
                    results.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));

                    tokenEstimate += output.length() / 4;
                    conversationLog.add("tool(" + toolName + "): " + output.substring(0, Math.min(200, output.length())));
                }
            }

            // ---- 7. Nag reminder (s03) ----
            roundsWithoutTodo = usedTodo ? 0 : roundsWithoutTodo + 1;
            if (todo.hasOpenItems() && roundsWithoutTodo >= 3) {
                results.add(0, ContentBlockParam.ofText(
                        TextBlockParam.builder().text("<reminder>Update your todos.</reminder>").build()));
            }

            paramsBuilder.addUserMessageOfBlockParams(results);

            // ---- 8. Manual compress ----
            if (manualCompress) {
                System.out.println("[manual compact]");
                doAutoCompact();
            }
        }
    }

    // ==================== Compression (s06) ====================

    private static void doAutoCompact() {
        try {
            Path transcriptDir = workDir.resolve(".transcripts");
            Files.createDirectories(transcriptDir);
            Path transcriptPath = transcriptDir.resolve("transcript_" + System.currentTimeMillis() + ".jsonl");

            // Save conversation log
            Files.writeString(transcriptPath, String.join("\n", conversationLog));
            System.out.println(Console.dim("[transcript saved: " + transcriptPath + "]"));

            // Generate summary via LLM
            String convText = String.join("\n", conversationLog);
            if (convText.length() > 80000) convText = convText.substring(convText.length() - 80000);

            Message summaryResp = client.messages().create(MessageCreateParams.builder()
                    .model(model).maxTokens(2000)
                    .addUserMessage("Summarize this conversation for continuity. Include: "
                            + "1) What was accomplished, 2) Current state, 3) Key decisions made. "
                            + "Be concise but preserve critical details.\n\n" + convText)
                    .build());

            var summarySb = new StringBuilder();
            for (ContentBlock b : summaryResp.content()) {
                b.text().ifPresent(tb -> summarySb.append(tb.text()));
            }
            String summary = summarySb.toString();

            // Rebuild paramsBuilder with compressed context
            paramsBuilder = MessageCreateParams.builder()
                    .model(model).maxTokens(8000).system(systemPrompt);
            for (Tool t : fullTools) paramsBuilder.addTool(t);

            paramsBuilder.addUserMessage("[Conversation compressed. Transcript: " + transcriptPath + "]\n\n" + summary);
            paramsBuilder.addAssistantMessage("Understood. I have the context from the summary. Continuing.");

            tokenEstimate = 0;
            conversationLog.clear();
            conversationLog.add("[compressed] " + summary);

            System.out.println("[auto-compact triggered]");
        } catch (Exception e) {
            System.err.println("Compression error: " + e.getMessage());
        }
    }

    // ==================== Client ====================

    private static AnthropicClient buildClient() {
        String baseUrl = EnvLoader.getBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            return AnthropicOkHttpClient.builder()
                    .apiKey(EnvLoader.getApiKey()).baseUrl(baseUrl).build();
        }
        return AnthropicOkHttpClient.builder()
                .apiKey(EnvLoader.getApiKey()).build();
    }

    // ==================== 23 Tool Definitions ====================

    private static List<Tool> buildFullToolList() {
        return List.of(
                // s01-s02: base
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

                // s03: TodoWrite
                AgentLoop.defineTool("TodoWrite", "Update task tracking list.",
                        Map.of("items", Map.of("type", "array",
                                "items", Map.of("type", "object",
                                        "properties", Map.of(
                                                "content", Map.of("type", "string"),
                                                "status", Map.of("type", "string",
                                                        "enum", List.of("pending", "in_progress", "completed")),
                                                "activeForm", Map.of("type", "string")),
                                        "required", List.of("content", "status", "activeForm")))),
                        List.of("items")),

                // s04: Subagent
                AgentLoop.defineTool("task", "Spawn a subagent for isolated exploration or work.",
                        Map.of("prompt", Map.of("type", "string"),
                                "agent_type", Map.of("type", "string",
                                        "enum", List.of("Explore", "general-purpose"))),
                        List.of("prompt")),

                // s05: Skills
                AgentLoop.defineTool("load_skill", "Load specialized knowledge by name.",
                        Map.of("name", Map.of("type", "string")), List.of("name")),

                // s06: Manual compress
                AgentLoop.defineTool("compress", "Manually compress conversation context.",
                        Map.of(), null),

                // s08: Background
                AgentLoop.defineTool("background_run", "Run command in background thread.",
                        Map.of("command", Map.of("type", "string"),
                                "timeout", Map.of("type", "integer")),
                        List.of("command")),
                AgentLoop.defineTool("check_background", "Check background task status.",
                        Map.of("task_id", Map.of("type", "string")), null),

                // s07: Tasks (self-contained)
                AgentLoop.defineTool("task_create", "Create a persistent file task.",
                        Map.of("subject", Map.of("type", "string"),
                                "description", Map.of("type", "string")),
                        List.of("subject")),
                AgentLoop.defineTool("task_get", "Get task details by ID.",
                        Map.of("task_id", Map.of("type", "integer")), List.of("task_id")),
                AgentLoop.defineTool("task_update", "Update task status or dependencies.",
                        Map.of("task_id", Map.of("type", "integer"),
                                "status", Map.of("type", "string",
                                        "enum", List.of("pending", "in_progress", "completed", "deleted")),
                                "add_blocked_by", Map.of("type", "array", "items", Map.of("type", "integer")),
                                "add_blocks", Map.of("type", "array", "items", Map.of("type", "integer"))),
                        List.of("task_id")),
                AgentLoop.defineTool("task_list", "List all tasks.", Map.of(), null),

                // s09/s11: Team
                AgentLoop.defineTool("spawn_teammate", "Spawn a persistent autonomous teammate.",
                        Map.of("name", Map.of("type", "string"),
                                "role", Map.of("type", "string"),
                                "prompt", Map.of("type", "string")),
                        List.of("name", "role", "prompt")),
                AgentLoop.defineTool("list_teammates", "List all teammates.", Map.of(), null),
                AgentLoop.defineTool("send_message", "Send a message to a teammate.",
                        Map.of("to", Map.of("type", "string"),
                                "content", Map.of("type", "string"),
                                "msg_type", Map.of("type", "string",
                                        "enum", List.of("message", "broadcast",
                                                "shutdown_request", "shutdown_response",
                                                "plan_approval_response"))),
                        List.of("to", "content")),
                AgentLoop.defineTool("read_inbox", "Read and drain the lead's inbox.",
                        Map.of(), null),
                AgentLoop.defineTool("broadcast", "Send message to all teammates.",
                        Map.of("content", Map.of("type", "string")), List.of("content")),

                // s10: Protocols
                AgentLoop.defineTool("shutdown_request", "Request a teammate to shut down.",
                        Map.of("teammate", Map.of("type", "string")), List.of("teammate")),
                AgentLoop.defineTool("plan_approval", "Approve or reject a teammate's plan.",
                        Map.of("request_id", Map.of("type", "string"),
                                "approve", Map.of("type", "boolean"),
                                "feedback", Map.of("type", "string")),
                        List.of("request_id", "approve")),

                // s11: Autonomous
                AgentLoop.defineTool("idle", "Enter idle state.", Map.of(), null),
                AgentLoop.defineTool("claim_task", "Claim a task from the board.",
                        Map.of("task_id", Map.of("type", "integer")), List.of("task_id"))
        );
    }

    // ==================== Tool Dispatchers ====================

    private static void registerDispatchers() {
        // s01-s02: base
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));

        // s03: TodoWrite
        dispatcher.register("TodoWrite", input -> {
            @SuppressWarnings("unchecked") List<?> items = (List<?>) input.get("items");
            return todo.update(items);
        });

        // s04: Subagent (needs client, deferred registration not needed since client is ready)
        dispatcher.register("task", input -> {
            String prompt = (String) input.get("prompt");
            String agentType = (String) input.getOrDefault("agent_type", "Explore");
            return runSubagent(prompt, agentType);
        });

        // s05: Skills
        dispatcher.register("load_skill", input -> skills.getContent((String) input.get("name")));

        // s06: Compress (flag only, actual compression in fullAgentLoop)
        dispatcher.register("compress", input -> "Compressing...");

        // s08: Background
        dispatcher.register("background_run", input ->
                bg.run((String) input.get("command"),
                        input.get("timeout") instanceof Number n ? n.intValue() : 120));
        dispatcher.register("check_background", input -> bg.check((String) input.get("task_id")));

        // s07: Tasks (self-contained)
        dispatcher.register("task_create", input ->
                createTask((String) input.get("subject"),
                        (String) input.getOrDefault("description", "")));
        dispatcher.register("task_get", input ->
                getTask(((Number) input.get("task_id")).intValue()));
        dispatcher.register("task_update", input -> {
            @SuppressWarnings("unchecked")
            var addBlockedBy = (List<Integer>) input.get("add_blocked_by");
            @SuppressWarnings("unchecked")
            var addBlocks = (List<Integer>) input.get("add_blocks");
            return updateTask(((Number) input.get("task_id")).intValue(),
                    (String) input.get("status"), addBlockedBy, addBlocks);
        });
        dispatcher.register("task_list", input -> listTasks());

        // s08: Inbox
        dispatcher.register("read_inbox", input -> {
            try {
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(bus.readInbox("lead"));
            } catch (Exception e) { return "[]"; }
        });

        // s11: Autonomous
        dispatcher.register("idle", input -> "Lead does not idle.");
        dispatcher.register("claim_task", input ->
                claimTask(((Number) input.get("task_id")).intValue(), "lead"));

        // s09: Team
        dispatcher.register("spawn_teammate", input ->
                spawnTeammate((String) input.get("name"),
                        (String) input.get("role"), (String) input.get("prompt")));
        dispatcher.register("list_teammates", input -> listAll());
        dispatcher.register("send_message", input ->
                bus.send("lead", (String) input.get("to"), (String) input.get("content"),
                        (String) input.getOrDefault("msg_type", "message"), null));
        dispatcher.register("broadcast", input ->
                bus.broadcast("lead", (String) input.get("content"), memberNames()));

        // s10: Protocols
        dispatcher.register("shutdown_request", input ->
                handleShutdownRequest((String) input.get("teammate")));
        dispatcher.register("plan_approval", input ->
                handlePlanReview((String) input.get("request_id"),
                        Boolean.TRUE.equals(input.get("approve")),
                        (String) input.getOrDefault("feedback", "")));
    }

    // ==================== Subagent (s04) ====================

    private static String runSubagent(String prompt, String agentType) {
        System.out.println("> task (" + agentType + "): " + prompt.substring(0, Math.min(80, prompt.length())));

        // Explore: 2 tools; general-purpose: 4 tools
        List<Tool> subTools = new ArrayList<>(List.of(
                AgentLoop.defineTool("bash", "Run command.",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
                AgentLoop.defineTool("read_file", "Read file.",
                        Map.of("path", Map.of("type", "string")), List.of("path"))
        ));
        if (!"Explore".equals(agentType)) {
            subTools.add(AgentLoop.defineTool("write_file", "Write file.",
                    Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")),
                    List.of("path", "content")));
            subTools.add(AgentLoop.defineTool("edit_file", "Edit file.",
                    Map.of("path", Map.of("type", "string"),
                            "old_text", Map.of("type", "string"),
                            "new_text", Map.of("type", "string")),
                    List.of("path", "old_text", "new_text")));
        }

        ToolDispatcher subDisp = new ToolDispatcher();
        subDisp.register("bash", input -> BashTool.execute(input, workDir));
        subDisp.register("read_file", input -> ReadTool.execute(input, sandbox));
        subDisp.register("write_file", input -> WriteTool.execute(input, sandbox));
        subDisp.register("edit_file", input -> EditTool.execute(input, sandbox));

        var subBuilder = MessageCreateParams.builder()
                .model(model).maxTokens(8000);
        for (Tool t : subTools) subBuilder.addTool(t);
        subBuilder.addUserMessage(prompt);

        for (int round = 0; round < 30; round++) {
            Message resp;
            try { resp = client.messages().create(subBuilder.build()); }
            catch (Exception e) { return "(subagent failed: " + e.getMessage() + ")"; }
            subBuilder.addMessage(resp);

            if (!resp.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                var texts = new ArrayList<String>();
                for (ContentBlock b : resp.content()) {
                    b.text().ifPresent(tb -> texts.add(tb.text()));
                }
                return texts.isEmpty() ? "(no summary)" : String.join("", texts);
            }

            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock b : resp.content()) {
                if (b.isToolUse()) {
                    ToolUseBlock tu = b.asToolUse();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> in = (Map<String, Object>) AgentLoop.jsonValueToObject(tu._input());
                    String out = subDisp.dispatch(tu.name(), in != null ? in : Map.of());
                    if (out.length() > 50000) out = out.substring(0, 50000);
                    results.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder().toolUseId(tu.id()).content(out).build()));
                }
            }
            subBuilder.addUserMessageOfBlockParams(results);
        }
        return "(subagent reached max rounds)";
    }

    // ==================== Teammate Lifecycle (s09/s11) ====================

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
     * 持久化 Teammate 生命周期：work→idle→work 循环，含 idle 轮询和自动认领。
     * <p>
     * 对应 Python: TeammateManager._loop
     */
    private static void teammateLoop(String name, String role, String prompt) {
        String teamName = (String) teamConfig.getOrDefault("team_name", "default");
        String sysPrompt = "You are '" + name + "', role: " + role + ", team: " + teamName
                + ", at " + workDir + ". Use idle when done with current work. You may auto-claim tasks.";

        var params = MessageCreateParams.builder()
                .model(model).maxTokens(8000).system(sysPrompt);

        // Teammate 工具（7 个）: bash, read, write, edit, send_message, idle, claim_task
        List<Tool> tools = List.of(
                AgentLoop.defineTool("bash", "Run command.",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
                AgentLoop.defineTool("read_file", "Read file.",
                        Map.of("path", Map.of("type", "string")), List.of("path")),
                AgentLoop.defineTool("write_file", "Write file.",
                        Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")),
                        List.of("path", "content")),
                AgentLoop.defineTool("edit_file", "Edit file.",
                        Map.of("path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),
                AgentLoop.defineTool("send_message", "Send message.",
                        Map.of("to", Map.of("type", "string"),
                                "content", Map.of("type", "string")),
                        List.of("to", "content")),
                AgentLoop.defineTool("idle", "Signal no more work.",
                        Map.of(), null),
                AgentLoop.defineTool("claim_task", "Claim task by ID.",
                        Map.of("task_id", Map.of("type", "integer")),
                        List.of("task_id"))
        );
        for (Tool t : tools) params.addTool(t);
        params.addUserMessage(prompt);

        // Teammate dispatcher
        ToolDispatcher disp = new ToolDispatcher();
        disp.register("bash", input -> BashTool.execute(input, workDir));
        disp.register("read_file", input -> ReadTool.execute(input, sandbox));
        disp.register("write_file", input -> WriteTool.execute(input, sandbox));
        disp.register("edit_file", input -> EditTool.execute(input, sandbox));
        disp.register("send_message", input ->
                bus.send(name, (String) input.get("to"), (String) input.get("content"),
                        "message", null));
        // idle: intercepted inline, not registered
        disp.register("claim_task", input ->
                claimTask(((Number) input.get("task_id")).intValue(), name));

        // ==================== Persistent lifecycle ====================
        while (true) {
            // ---- WORK PHASE ----
            boolean idleRequested = false;

            for (int round = 0; round < 50; round++) {
                var inbox = bus.readInbox(name);
                for (var msg : inbox) {
                    if ("shutdown_request".equals(msg.get("type"))) {
                        setMemberStatus(name, "shutdown");
                        return;
                    }
                    try { params.addUserMessage(MAPPER.writeValueAsString(msg)); }
                    catch (IOException ignored) {}
                }

                try {
                    Message resp = client.messages().create(params.build());
                    params.addMessage(resp);

                    if (!resp.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) break;

                    List<ContentBlockParam> results = new ArrayList<>();
                    for (ContentBlock block : resp.content()) {
                        if (block.isToolUse()) {
                            ToolUseBlock tu = block.asToolUse();

                            // Inline idle interception
                            if ("idle".equals(tu.name())) {
                                idleRequested = true;
                                String output = "Entering idle phase.";
                                System.out.println(Console.dim("  [" + name + "] idle: " + output));
                                results.add(ContentBlockParam.ofToolResult(
                                        ToolResultBlockParam.builder().toolUseId(tu.id()).content(output).build()));
                                continue;
                            }

                            @SuppressWarnings("unchecked")
                            Map<String, Object> input = (Map<String, Object>)
                                    AgentLoop.jsonValueToObject(tu._input());
                            String output = disp.dispatch(tu.name(), input != null ? input : Map.of());

                            System.out.println(Console.dim("  [" + name + "] " + tu.name() + ": "
                                    + output.substring(0, Math.min(120, output.length()))));
                            results.add(ContentBlockParam.ofToolResult(
                                    ToolResultBlockParam.builder().toolUseId(tu.id()).content(output).build()));
                        }
                    }
                    params.addUserMessageOfBlockParams(results);
                    if (idleRequested) break;
                } catch (Exception e) {
                    System.out.println(Console.toolError("[" + name + "]", e.getMessage()));
                    setMemberStatus(name, "shutdown");
                    return;
                }
            }

            // ---- IDLE PHASE: poll inbox + unclaimed tasks ----
            setMemberStatus(name, "idle");
            boolean resume = false;
            int polls = IDLE_TIMEOUT_SECONDS / Math.max(POLL_INTERVAL_SECONDS, 1);

            for (int p = 0; p < polls; p++) {
                try { Thread.sleep(POLL_INTERVAL_SECONDS * 1000L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); setMemberStatus(name, "shutdown"); return; }

                var inbox = bus.readInbox(name);
                if (!inbox.isEmpty()) {
                    for (var msg : inbox) {
                        if ("shutdown_request".equals(msg.get("type"))) {
                            setMemberStatus(name, "shutdown");
                            return;
                        }
                        try { params.addUserMessage(MAPPER.writeValueAsString(msg)); }
                        catch (IOException ignored) {}
                    }
                    resume = true;
                    break;
                }

                var unclaimed = scanUnclaimedTasks();
                if (!unclaimed.isEmpty()) {
                    var task = unclaimed.get(0);
                    int taskId = ((Number) task.get("id")).intValue();
                    claimTask(taskId, name);

                    // Identity re-injection
                    params.addUserMessage("<identity>You are '" + name + "', role: " + role
                            + ", team: " + teamName + ". Continue your work.</identity>");
                    params.addAssistantMessage("I am " + name + ". Continuing.");

                    String taskPrompt = "<auto-claimed>Task #" + task.get("id") + ": " + task.get("subject")
                            + "\n" + task.getOrDefault("description", "") + "</auto-claimed>";
                    params.addUserMessage(taskPrompt);
                    params.addAssistantMessage("Claimed task #" + taskId + ". Working on it.");

                    resume = true;
                    break;
                }
            }

            if (!resume) { setMemberStatus(name, "shutdown"); return; }
            setMemberStatus(name, "working");
        }
    }

    // ==================== Task Management (self-contained, s07) ====================

    private static int maxTaskId() {
        try (var stream = Files.list(tasksDir)) {
            return stream.filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .mapToInt(p -> Integer.parseInt(p.getFileName().toString()
                            .replaceAll("task_(\\d+)\\.json", "$1")))
                    .max().orElse(0);
        } catch (IOException e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static synchronized String createTask(String subject, String description) {
        var task = new LinkedHashMap<String, Object>();
        task.put("id", nextTaskId);
        task.put("subject", subject);
        task.put("description", description != null ? description : "");
        task.put("status", "pending");
        task.put("owner", "");
        task.put("blockedBy", new ArrayList<Integer>());
        task.put("blocks", new ArrayList<Integer>());
        try {
            Files.writeString(tasksDir.resolve("task_" + nextTaskId + ".json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task));
        } catch (IOException e) { return "Error: " + e.getMessage(); }
        nextTaskId++;
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task); }
        catch (IOException e) { return task.toString(); }
    }

    private static String getTask(int taskId) {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(loadTask(taskId)); }
        catch (IOException e) { return "Error: " + e.getMessage(); }
    }

    @SuppressWarnings("unchecked")
    private static synchronized String updateTask(int taskId, String status,
                                                   List<Integer> addBlockedBy, List<Integer> addBlocks) {
        var task = loadTask(taskId);
        if (status != null) {
            if (!List.of("pending", "in_progress", "completed", "deleted").contains(status))
                throw new IllegalArgumentException("Invalid status: " + status);
            task.put("status", status);
            if ("completed".equals(status)) clearDependency(taskId);
            if ("deleted".equals(status)) {
                try { Files.deleteIfExists(tasksDir.resolve("task_" + taskId + ".json")); return "Task " + taskId + " deleted"; }
                catch (IOException e) { return "Error: " + e.getMessage(); }
            }
        }
        if (addBlockedBy != null) {
            var set = new LinkedHashSet<>((List<Integer>) task.getOrDefault("blockedBy", new ArrayList<>()));
            set.addAll(addBlockedBy);
            task.put("blockedBy", new ArrayList<>(set));
        }
        if (addBlocks != null) {
            var set = new LinkedHashSet<>((List<Integer>) task.getOrDefault("blocks", new ArrayList<>()));
            set.addAll(addBlocks);
            task.put("blocks", new ArrayList<>(set));
        }
        saveTask(task);
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task); }
        catch (IOException e) { return task.toString(); }
    }

    private static synchronized String claimTask(int taskId, String owner) {
        var task = loadTask(taskId);
        task.put("owner", owner);
        task.put("status", "in_progress");
        saveTask(task);
        return "Claimed task #" + taskId + " for " + owner;
    }

    private static String listTasks() {
        try (var stream = Files.list(tasksDir)) {
            var tasks = stream.filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .sorted().map(p -> { try { return MAPPER.readValue(Files.readString(p), Map.class); }
                        catch (IOException e) { return null; } })
                    .filter(Objects::nonNull).toList();
            if (tasks.isEmpty()) return "No tasks.";
            var lines = new ArrayList<String>();
            for (var t : tasks) {
                String s = (String) t.getOrDefault("status", "?");
                String m = switch (s) { case "pending" -> "[ ]"; case "in_progress" -> "[>]";
                    case "completed" -> "[x]"; default -> "[?]"; };
                String owner = t.get("owner") != null && !t.get("owner").toString().isEmpty()
                        ? " @" + t.get("owner") : "";
                lines.add(m + " #" + t.get("id") + ": " + t.get("subject") + owner);
            }
            return String.join("\n", lines);
        } catch (IOException e) { return "Error: " + e.getMessage(); }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> scanUnclaimedTasks() {
        try (var stream = Files.list(tasksDir)) {
            var result = new ArrayList<Map<String, Object>>();
            for (var path : stream.filter(p -> p.getFileName().toString().matches("task_\\d+\\.json")).sorted().toList()) {
                try {
                    var task = (Map<String, Object>) MAPPER.readValue(Files.readString(path), Map.class);
                    String status = (String) task.getOrDefault("status", "");
                    String owner = (String) task.getOrDefault("owner", "");
                    List<Integer> blockedBy = (List<Integer>) task.getOrDefault("blockedBy", List.of());
                    if ("pending".equals(status) && (owner == null || owner.isEmpty()) && blockedBy.isEmpty())
                        result.add(task);
                } catch (IOException ignored) {}
            }
            return result;
        } catch (IOException e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadTask(int taskId) {
        Path path = tasksDir.resolve("task_" + taskId + ".json");
        if (!Files.exists(path)) throw new IllegalArgumentException("Task " + taskId + " not found");
        try { return MAPPER.readValue(Files.readString(path), Map.class); }
        catch (IOException e) { throw new RuntimeException(e.getMessage()); }
    }

    private static void saveTask(Map<String, Object> task) {
        try { Files.writeString(tasksDir.resolve("task_" + task.get("id") + ".json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task)); }
        catch (IOException e) { throw new RuntimeException(e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private static void clearDependency(int completedId) {
        try (var stream = Files.list(tasksDir)) {
            stream.filter(p -> p.getFileName().toString().matches("task_\\d+\\.json")).forEach(path -> {
                try {
                    var task = MAPPER.readValue(Files.readString(path), Map.class);
                    List<Integer> blockedBy = (List<Integer>) task.getOrDefault("blockedBy", List.of());
                    if (blockedBy.contains(completedId)) {
                        blockedBy = new ArrayList<>(blockedBy);
                        blockedBy.remove(Integer.valueOf(completedId));
                        task.put("blockedBy", blockedBy);
                        saveTask(task);
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    // ==================== Team Config (self-contained) ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadTeamConfig() {
        try { Files.createDirectories(teamDir); } catch (IOException ignored) {}
        if (Files.exists(configPath)) {
            try { return MAPPER.readValue(Files.readString(configPath), Map.class); }
            catch (IOException ignored) {}
        }
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("team_name", "default");
        cfg.put("members", new ArrayList<Map<String, Object>>());
        return cfg;
    }

    private static synchronized void saveTeamConfig() {
        try { Files.writeString(configPath, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(teamConfig)); }
        catch (IOException ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static synchronized Map<String, Object> findMember(String name) {
        var members = (List<Map<String, Object>>) teamConfig.get("members");
        return members.stream().filter(m -> name.equals(m.get("name"))).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static String listAll() {
        var members = (List<Map<String, Object>>) teamConfig.get("members");
        if (members.isEmpty()) return "No teammates.";
        var lines = new ArrayList<String>();
        lines.add("Team: " + teamConfig.get("team_name"));
        for (var m : members) lines.add("  " + m.get("name") + " (" + m.get("role") + "): " + m.get("status"));
        return String.join("\n", lines);
    }

    @SuppressWarnings("unchecked")
    private static List<String> memberNames() {
        var members = (List<Map<String, Object>>) teamConfig.get("members");
        return members.stream().map(m -> (String) m.get("name")).toList();
    }

    private static synchronized void setMemberStatus(String name, String status) {
        var member = findMember(name);
        if (member != null) { member.put("status", status); saveTeamConfig(); }
    }

    // ==================== Protocol Handlers (s10) ====================

    private static String handleShutdownRequest(String teammate) {
        String reqId = UUID.randomUUID().toString().substring(0, 8);
        shutdownRequests.put(reqId, new ConcurrentHashMap<>(Map.of("target", teammate, "status", "pending")));
        bus.send("lead", teammate, "Please shut down.", "shutdown_request",
                Map.of("request_id", reqId));
        return "Shutdown request " + reqId + " sent to '" + teammate + "'";
    }

    private static String handlePlanReview(String requestId, boolean approve, String feedback) {
        var req = planRequests.get(requestId);
        if (req == null) return "Error: Unknown plan request_id '" + requestId + "'";
        req.put("status", approve ? "approved" : "rejected");
        bus.send("lead", (String) req.get("from"), feedback != null ? feedback : "",
                "plan_approval_response", Map.of("request_id", requestId, "approve", approve,
                        "feedback", feedback != null ? feedback : ""));
        return "Plan " + req.get("status") + " for '" + req.get("from") + "'";
    }
}
