package com.example.agent.sessions;

import com.anthropic.models.messages.*;
import com.example.agent.background.BackgroundManager;
import com.example.agent.compress.ContextCompressor;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.skills.SkillLoader;
import com.example.agent.tasks.TaskManager;
import com.example.agent.tasks.TodoManager;
import com.example.agent.team.MessageBus;
import com.example.agent.team.TeamManager;
import com.example.agent.team.TeamProtocol;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.PathSandbox;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * SFull：全量参考实现 —— 所有机制的完整集成。
 * <p>
 * 这不是教学课程，而是"把所有东西组合起来"的参考实现。
 * 整合了 s01-s11 的全部机制：
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │                        FULL AGENT                            │
 * │                                                              │
 * │  System prompt (s05 skills, task-first + optional todo nag) │
 * │                                                              │
 * │  Before each LLM call:                                       │
 * │  ┌──────────────────┐ ┌────────────────┐ ┌──────────────┐  │
 * │  │ Microcompact(s06)│ │ Drain bg (s08) │ │ Check inbox  │  │
 * │  │ Auto-compact(s06)│ │ notifications  │ │ (s09)        │  │
 * │  └──────────────────┘ └────────────────┘ └──────────────┘  │
 * │                                                              │
 * │  Tool dispatch (s02 pattern): 22 个工具                      │
 * │  ┌────────┬──────────┬──────────┬─────────┬───────────┐    │
 * │  │ bash   │ read     │ write    │ edit    │ TodoWrite │    │
 * │  │ task   │ load_sk  │ compress │ bg_run  │ bg_check  │    │
 * │  │ t_crt  │ t_get    │ t_upd   │ t_list  │ spawn_tm  │    │
 * │  │ list_tm│ send_msg │ rd_inbox │ bcast   │ shutdown  │    │
 * │  │ plan   │ idle     │ claim   │         │           │    │
 * │  └────────┴──────────┴──────────┴─────────┴───────────┘    │
 * │                                                              │
 * │  REPL commands: /compact /tasks /team /inbox                │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * 对应 Python 原版：s_full.py（~737 行）
 */
public class SFullAgent {

    private static final Logger log = LoggerFactory.getLogger(SFullAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 自动压缩阈值（token 数） */
    private static final long TOKEN_THRESHOLD = 100000;

    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);

        // ==================== 初始化所有模块 ====================

        // s03: TodoManager
        TodoManager todo = new TodoManager();

        // s05: SkillLoader
        SkillLoader skills = new SkillLoader(workDir.resolve("skills"));

        // s06: ContextCompressor（延迟初始化，需要 client）
        Path transcriptDir = workDir.resolve(".transcripts");

        // s07: TaskManager
        TaskManager taskMgr = new TaskManager(workDir.resolve(".tasks"));

        // s08: BackgroundManager
        BackgroundManager bg = new BackgroundManager(workDir);

        // s09: MessageBus
        MessageBus bus = new MessageBus(workDir.resolve(".team").resolve("inbox"));

        // s10: TeamProtocol
        TeamProtocol protocol = new TeamProtocol(bus);

        // ==================== 系统提示词 ====================
        String systemPrompt = "You are a coding agent at " + workDir + ". Use tools to solve tasks.\n"
                + "Prefer task_create/task_update/task_list for multi-step work. "
                + "Use TodoWrite for short checklists.\n"
                + "Use task for subagent delegation. Use load_skill for specialized knowledge.\n"
                + "Skills: " + skills.getDescriptions();

        // ==================== 22 个工具定义 ====================
        List<Tool> tools = buildFullToolList();

        // ==================== 工具分发器 ====================
        ToolDispatcher dispatcher = new ToolDispatcher();

        // 基础工具 (s01-s02)
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));

        // s03: TodoWrite
        dispatcher.register("TodoWrite", input -> {
            @SuppressWarnings("unchecked")
            List<?> items = (List<?>) input.get("items");
            return todo.update(items);
        });

        // s05: 技能加载
        dispatcher.register("load_skill", input -> skills.getContent((String) input.get("name")));

        // s06: 手动压缩（标志位，实际压缩在循环后执行）
        dispatcher.register("compress", input -> "Compressing...");

        // s07: 任务管理
        dispatcher.register("task_create", input ->
                taskMgr.create((String) input.get("subject"),
                        (String) input.getOrDefault("description", "")));
        dispatcher.register("task_get", input ->
                taskMgr.get(((Number) input.get("task_id")).intValue()));
        dispatcher.register("task_update", input -> {
            @SuppressWarnings("unchecked")
            var addBlockedBy = (List<Integer>) input.get("add_blocked_by");
            @SuppressWarnings("unchecked")
            var addBlocks = (List<Integer>) input.get("add_blocks");
            return taskMgr.update(((Number) input.get("task_id")).intValue(),
                    (String) input.get("status"), addBlockedBy, addBlocks);
        });
        dispatcher.register("task_list", input -> taskMgr.listAll());

        // s08: 后台任务
        dispatcher.register("background_run", input ->
                bg.run((String) input.get("command"),
                        input.get("timeout") instanceof Number n ? n.intValue() : 120));
        dispatcher.register("check_background", input ->
                bg.check((String) input.get("task_id")));

        // s09: 消息（先注册占位，TeamManager 创建后补充）
        dispatcher.register("read_inbox", input -> {
            try {
                return MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(bus.readInbox("lead"));
            } catch (Exception e) { return "[]"; }
        });

        // s11: idle / claim_task
        dispatcher.register("idle", input -> "Lead does not idle.");
        dispatcher.register("claim_task", input ->
                taskMgr.claim(((Number) input.get("task_id")).intValue(), "lead"));

        // ==================== 创建 Agent 循环 ====================
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);

        // 需要 agent 实例后才能初始化的组件
        ContextCompressor compressor = new ContextCompressor(
                agent.getClient(), agent.getModel(), transcriptDir);
        TeamManager team = new TeamManager(
                workDir.resolve(".team"), bus,
                agent.getClient(), agent.getModel(), workDir, sandbox);

        // s04: Subagent（需要 agent 实例）
        dispatcher.register("task", input -> {
            String prompt = (String) input.get("prompt");
            String desc = (String) input.getOrDefault("agent_type", "Explore");
            System.out.println("> task (" + desc + "): " + prompt.substring(0, Math.min(80, prompt.length())));
            return runSubagent(agent, prompt);
        });

        // s09: 团队工具（需要 team 实例）
        dispatcher.register("spawn_teammate", input ->
                team.spawn((String) input.get("name"), (String) input.get("role"), (String) input.get("prompt")));
        dispatcher.register("list_teammates", input -> team.listAll());
        dispatcher.register("send_message", input ->
                bus.send("lead", (String) input.get("to"), (String) input.get("content"),
                        (String) input.getOrDefault("msg_type", "message"), null));
        dispatcher.register("broadcast", input ->
                bus.broadcast("lead", (String) input.get("content"), team.memberNames()));

        // s10: 协议工具
        dispatcher.register("shutdown_request", input ->
                protocol.requestShutdown((String) input.get("teammate")));
        dispatcher.register("plan_approval", input ->
                protocol.reviewPlan((String) input.get("request_id"),
                        Boolean.TRUE.equals(input.get("approve")),
                        (String) input.getOrDefault("feedback", "")));

        // ==================== REPL 主循环 ====================
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   mini-agent-4j SFullAgent (Java 21)    ║");
        System.out.println("║   22 tools | /compact /tasks /team /inbox║");
        System.out.println("╚══════════════════════════════════════════╝");

        while (true) {
            System.out.print("\033[36ms_full >> \033[0m");
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            // ---- REPL 斜杠命令 ----
            switch (query) {
                case "/compact" -> {
                    System.out.println("[manual compact via /compact]");
                    // 注意：简化实现，实际的压缩需要操作 messages 列表
                    continue;
                }
                case "/tasks" -> {
                    System.out.println(taskMgr.listAll());
                    continue;
                }
                case "/team" -> {
                    System.out.println(team.listAll());
                    continue;
                }
                case "/inbox" -> {
                    try {
                        System.out.println(MAPPER.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(bus.readInbox("lead")));
                    } catch (Exception e) {
                        System.out.println("[]");
                    }
                    continue;
                }
                default -> {}
            }

            // ---- 预处理：drain 后台通知 (s08) ----
            var notifs = bg.drainNotifications();
            if (!notifs.isEmpty()) {
                var sb = new StringBuilder();
                for (var n : notifs) {
                    sb.append("[bg:").append(n.get("task_id")).append("] ")
                            .append(n.get("status")).append(": ")
                            .append(n.get("result")).append("\n");
                }
                paramsBuilder.addUserMessage("<background-results>\n" + sb.toString().trim() + "\n</background-results>");
                paramsBuilder.addAssistantMessage("Noted background results.");
            }

            // ---- 预处理：drain lead inbox (s09) ----
            var inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                try {
                    paramsBuilder.addUserMessage("<inbox>" + MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(inbox) + "</inbox>");
                    paramsBuilder.addAssistantMessage("Noted inbox messages.");
                } catch (Exception ignored) {}
            }

            // ---- 用户消息 + Agent 循环 ----
            paramsBuilder.addUserMessage(query);
            try {
                agent.agentLoop(paramsBuilder);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
    }

    // ==================== Subagent (s04) ====================

    /**
     * 运行子 Agent：全新上下文，最多 30 轮。
     */
    private static String runSubagent(AgentLoop agent, String prompt) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);

        List<Tool> subTools = List.of(
                AgentLoop.defineTool("bash", "Run command.",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
                AgentLoop.defineTool("read_file", "Read file.",
                        Map.of("path", Map.of("type", "string")), List.of("path"))
        );

        ToolDispatcher subDispatcher = new ToolDispatcher();
        subDispatcher.register("bash", input -> BashTool.execute(input, workDir));
        subDispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));

        var subBuilder = MessageCreateParams.builder()
                .model(agent.getModel())
                .maxTokens(8000);
        for (Tool t : subTools) subBuilder.addTool(t);
        subBuilder.addUserMessage(prompt);

        for (int round = 0; round < 30; round++) {
            Message resp = agent.getClient().messages().create(subBuilder.build());
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
                    String out = subDispatcher.dispatch(tu.name(), in != null ? in : Map.of());
                    results.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder().toolUseId(tu.id())
                                    .content(out.length() > 50000 ? out.substring(0, 50000) : out).build()));
                }
            }
            subBuilder.addUserMessageOfBlockParams(results);
        }
        return "(subagent reached max rounds)";
    }

    // ==================== 22 个工具定义 ====================

    private static List<Tool> buildFullToolList() {
        return List.of(
                // s01-s02: 基础工具
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

                // s05: 技能加载
                AgentLoop.defineTool("load_skill", "Load specialized knowledge by name.",
                        Map.of("name", Map.of("type", "string")), List.of("name")),

                // s06: 手动压缩
                AgentLoop.defineTool("compress", "Manually compress conversation context.",
                        Map.of(), null),

                // s08: 后台任务
                AgentLoop.defineTool("background_run", "Run command in background thread.",
                        Map.of("command", Map.of("type", "string"),
                                "timeout", Map.of("type", "integer")),
                        List.of("command")),
                AgentLoop.defineTool("check_background", "Check background task status.",
                        Map.of("task_id", Map.of("type", "string")), null),

                // s07: 持久化任务
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
                AgentLoop.defineTool("task_list", "List all tasks.",
                        Map.of(), null),

                // s09: 团队
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

                // s10: 协议
                AgentLoop.defineTool("shutdown_request", "Request a teammate to shut down.",
                        Map.of("teammate", Map.of("type", "string")), List.of("teammate")),
                AgentLoop.defineTool("plan_approval", "Approve or reject a teammate's plan.",
                        Map.of("request_id", Map.of("type", "string"),
                                "approve", Map.of("type", "boolean"),
                                "feedback", Map.of("type", "string")),
                        List.of("request_id", "approve")),

                // s11: 自治
                AgentLoop.defineTool("idle", "Enter idle state.",
                        Map.of(), null),
                AgentLoop.defineTool("claim_task", "Claim a task from the board.",
                        Map.of("task_id", Map.of("type", "integer")), List.of("task_id"))
        );
    }
}
