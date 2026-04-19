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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SFull：全量参考实现 —— 所有 s01-s18 机制的完整集成（自包含）。
 * <p>
 * 整合 s01-s18 全部机制：
 * <pre>
 * ┌──────────────────────────────────────────────────────────────┐
 * │                        FULL AGENT                            │
 * │                                                              │
 * │  System prompt (skills, task-first + optional todo nag)      │
 * │                                                              │
 * │  Before each LLM call:                                       │
 * │  ┌──────────────────┐ ┌────────────────┐ ┌──────────────┐  │
 * │  │ Auto-compact(s06)│ │ Drain bg (s13) │ │ Check inbox  │  │
 * │  │ Token threshold  │ │ notifications  │ │ (s15)        │  │
 * │  └──────────────────┘ └────────────────┘ └──────────────┘  │
 * │                                                              │
 * │  Tool dispatch (s02): 23 个工具                              │
 * │  After tool: Nag reminder (s03) if needed                   │
 * │  Manual compress (s06): replaces history                     │
 * │                                                              │
 * │  Teammate (s17): persistent lifecycle, auto-claim tasks      │
 * │  Protocols (s16): shutdown_request + plan_approval           │
 * └──────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * 对应 Python 原版：s_full.py
 */
public class SFullAgent {

    // ==================== 常量定义 ====================

    private static final Path WORKDIR = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    private static final long MAX_TOKENS = 8000;
    private static final int BASH_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT = 50000;
    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_CYAN = "\033[36m";
    private static final String ANSI_DIM = "\033[2m";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 主动压缩的 token 估算阈值（字符数 / 4 约等于 token 数） */
    private static final long TOKEN_THRESHOLD = 100000;
    /** Teammate IDLE 阶段轮询间隔（秒） */
    private static final int POLL_INTERVAL_SECONDS = 5;
    /** Teammate IDLE 阶段超时（秒），超时后自动 shutdown */
    private static final int IDLE_TIMEOUT_SECONDS = 60;
    /** Microcompact: 保留最近 N 个 tool_result turn */
    private static final int KEEP_RECENT = 3;
    /** Microcompact: 这些工具的结果不压缩 */
    private static final Set<String> PRESERVE_RESULT_TOOLS = Set.of("read_file");

    /** 合法的消息类型（s16 协议验证） */
    private static final Set<String> VALID_MSG_TYPES = Set.of("message", "broadcast", "shutdown_request", "shutdown_response", "plan_approval_response");

    /** 持久化输出阈值（字符数） */
    private static final int PERSIST_TRIGGER_DEFAULT = 50000;
    private static final int PERSIST_TRIGGER_BASH = 30000;
    private static final int PERSISTED_PREVIEW_CHARS = 2000;

    // ---- 路径 ----
    private static final Path TASKS_DIR = WORKDIR.resolve(".tasks");
    private static final Path TEAM_DIR = WORKDIR.resolve(".team");
    private static final Path INBOX_DIR = TEAM_DIR.resolve("inbox");
    private static final Path SKILLS_DIR = WORKDIR.resolve("skills");
    private static final Path TRANSCRIPT_DIR = WORKDIR.resolve(".transcripts");
    private static final Path CONFIG_PATH = TEAM_DIR.resolve("config.json");
    private static final Path TASK_OUTPUT_DIR = WORKDIR.resolve(".task_outputs");
    private static final Path TOOL_RESULTS_DIR = TASK_OUTPUT_DIR.resolve("tool-results");

    // ---- 全局模块 ----
    private static AnthropicClient client;
    private static String modelId;
    private static TodoManager todo;
    private static SkillRegistry skills;
    private static BackgroundManager bg;
    private static MessageBus bus;

    // ---- 任务状态 ----
    private static int nextTaskId;

    // ---- 团队配置 ----
    private static Map<String, Object> teamConfig;

    // ---- 协议状态 ----
    private static final ConcurrentHashMap<String, Map<String, Object>> shutdownRequests = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Map<String, Object>> planRequests = new ConcurrentHashMap<>();

    // ---- 压缩追踪 ----
    private static long tokenEstimate = 0;
    private static final List<String> conversationLog = new ArrayList<>();
    private static List<Object> messageHistory = new ArrayList<>();

    // ---- 主循环状态 ----
    private static MessageCreateParams.Builder paramsBuilder;
    private static String systemPrompt;
    private static List<Tool> fullTools;

    // ==================== 主入口 ====================

    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        // ---- 加载环境变量 ----
        Dotenv dotenv = Dotenv.configure().directory(WORKDIR.toString()).ignoreIfMissing().load();
        String baseUrl = dotenv.get("ANTHROPIC_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) System.clearProperty("ANTHROPIC_AUTH_TOKEN");
        String apiKey = dotenv.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("ANTHROPIC_API_KEY 未配置");
        modelId = dotenv.get("MODEL_ID");
        if (modelId == null || modelId.isBlank()) throw new IllegalStateException("MODEL_ID 未配置");

        // ---- 构建客户端 ----
        client = buildClient(apiKey, baseUrl);

        // ---- 初始化模块 ----
        todo = new TodoManager();
        skills = new SkillRegistry();
        bg = new BackgroundManager();
        bus = new MessageBus();
        teamConfig = loadTeamConfig();

        try { Files.createDirectories(TASKS_DIR); } catch (IOException ignored) {}
        try { Files.createDirectories(TRANSCRIPT_DIR); } catch (IOException ignored) {}
        nextTaskId = maxTaskId() + 1;

        systemPrompt = "You are a coding agent at " + WORKDIR + ". Use tools to solve tasks.\n"
                + "Prefer task_create/task_update/task_list for multi-step work. "
                + "Use TodoWrite for short checklists.\n"
                + "Use task for subagent delegation. Use load_skill for specialized knowledge.\n"
                + "Skills:\n" + skills.descriptions();

        // ---- 工具定义 ----
        fullTools = buildFullToolList();

        // ---- 工具分发 ----
        Map<String, java.util.function.Function<Map<String, Object>, String>> dispatch = new LinkedHashMap<>();
        registerDispatchers(dispatch);

        // ---- 构建参数 ----
        paramsBuilder = MessageCreateParams.builder().model(modelId).maxTokens(MAX_TOKENS).system(systemPrompt);
        for (Tool t : fullTools) paramsBuilder.addTool(t);

        // ---- REPL ----
        Scanner scanner = new Scanner(System.in);
        System.out.println(ANSI_CYAN + "mini-agent-4j SFullAgent | 23 tools | /compact /tasks /team /inbox" + ANSI_RESET);

        while (true) {
            System.out.print(ansiCyan("s_full >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            switch (query) {
                case "/compact" -> {
                    if (!conversationLog.isEmpty()) { System.out.println("[manual compact]"); doAutoCompact(messageHistory, null); }
                    continue;
                }
                case "/tasks" -> { System.out.println(listTasks()); continue; }
                case "/team" -> { System.out.println(listAll()); continue; }
                case "/inbox" -> {
                    try { var msgs = bus.readInbox("lead"); System.out.println(msgs.isEmpty() ? "[]" : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(msgs)); }
                    catch (Exception e) { System.out.println("[]"); }
                    continue;
                }
                default -> {}
            }

            paramsBuilder.addUserMessage(query);
            messageHistory.add(query);
            tokenEstimate += query.length() / 4;
            conversationLog.add("user: " + query);

            try { fullAgentLoop(dispatch); }
            catch (Exception e) { System.err.println("Error: " + e.getMessage()); }
            System.out.println();
        }
        System.out.println("Bye!");
    }

    // ==================== 完整 Agent 循环 ====================
    //
    // 整合了 s01-s18 的全部机制，每次 LLM 调用前执行三步预处理：
    //   1. Auto-compact（s06）：token 估算超过阈值时自动压缩对话历史
    //   2. Drain 后台通知（s13）：将后台任务完成结果注入对话
    //   3. Drain lead 收件箱（s15）：将 teammate 消息注入对话
    //
    // 工具执行后还有两个后处理步骤：
    //   6. Nag reminder（s03）：连续 3 轮未更新 todo 时插入提醒
    //   7. Manual compress（s06）：如果 LLM 调用了 compress 工具，触发压缩

    private static void fullAgentLoop(Map<String, java.util.function.Function<Map<String, Object>, String>> dispatch) {
        int roundsWithoutTodo = 0;

        while (true) {
            // ---- 1. Microcompact ----
            microcompact(messageHistory);

            // ---- 2. Auto-compact ----
            if (tokenEstimate > TOKEN_THRESHOLD) doAutoCompact(messageHistory, null);

            // ---- 3. Drain 后台通知 ----
            var notifs = bg.drain();
            if (!notifs.isEmpty()) {
                var sb = new StringBuilder("<background-results>\n");
                for (var n : notifs) sb.append("[bg:").append(n.get("task_id")).append("] ").append(n.get("status")).append(": ").append(n.get("result")).append("\n");
                sb.append("</background-results>");
                String txt = sb.toString();
                System.out.println(ANSI_YELLOW + txt.replace("<background-results>\n","").replace("</background-results>","").trim() + ANSI_RESET);
                messageHistory.add(txt); messageHistory.add("__ack_bg__");
            }

            // ---- 4. Drain lead inbox ----
            var inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                try { messageHistory.add("<inbox>" + MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(inbox) + "</inbox>");
                    messageHistory.add("__ack_inbox__");
                } catch (Exception ignored) {}
            }

            // ---- 5. Rebuild paramsBuilder from history ----
            rebuildFromHistory(messageHistory);

            // ---- 6. LLM 调用 ----
            Message response = client.messages().create(paramsBuilder.build());
            messageHistory.add(response);

            for (ContentBlock block : response.content()) {
                block.text().ifPresent(tb -> { tokenEstimate += tb.text().length()/4; conversationLog.add("assistant: "+tb.text()); });
            }

            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                for (ContentBlock block : response.content()) block.text().ifPresent(tb -> System.out.println(tb.text()));
                return;
            }

            // ---- 7. 工具执行 ----
            List<ContentBlockParam> results = new ArrayList<>();
            List<String> resultToolNames = new ArrayList<>();
            List<String> resultToolUseIds = new ArrayList<>();
            boolean usedTodo = false;
            boolean manualCompress = false;
            String compactFocus = null;

            for (ContentBlock block : response.content()) {
                if (!block.isToolUse()) continue;
                ToolUseBlock tu = block.asToolUse();
                @SuppressWarnings("unchecked") Map<String,Object> input = (Map<String,Object>) jsonValueToObject(tu._input());
                if (input == null) input = Map.of();
                if (!(input instanceof LinkedHashMap)) { var m = new LinkedHashMap<>(input); input = m; }
                input.put("_tool_use_id", tu.id());
                if ("compress".equals(tu.name())) { manualCompress = true; compactFocus = input.get("focus") instanceof String s ? s : null; }
                if ("TodoWrite".equals(tu.name())) usedTodo = true;

                var handler = dispatch.get(tu.name());
                String output;
                try { output = handler != null ? handler.apply(input) : "Unknown: "+tu.name(); }
                catch (Exception e) { output = "Error: "+e.getMessage(); }

                System.out.println(ANSI_DIM + "> " + tu.name() + ": " + output.substring(0, Math.min(200, output.length())) + ANSI_RESET);
                if (output.length() > MAX_OUTPUT) output = output.substring(0, MAX_OUTPUT);

                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(output).build()));
                resultToolNames.add(tu.name());
                resultToolUseIds.add(tu.id());
                tokenEstimate += output.length()/4;
                conversationLog.add("tool("+tu.name()+"): "+output.substring(0, Math.min(200,output.length())));
            }

            // ---- 8. Nag reminder（s03 机制） ----
            roundsWithoutTodo = usedTodo ? 0 : roundsWithoutTodo + 1;
            if (todo.hasOpenItems() && roundsWithoutTodo >= 3) {
                results.add(0, ContentBlockParam.ofText(TextBlockParam.builder().text("<reminder>Update your todos.</reminder>").build()));
            }

            var turnInfo = new LinkedHashMap<Object,Object>();
            turnInfo.put("blocks", results);
            turnInfo.put("tools", resultToolNames);
            turnInfo.put("ids", resultToolUseIds);
            messageHistory.add(turnInfo);

            // ---- 9. Manual compress ----
            if (manualCompress) { System.out.println("[manual compact]"); doAutoCompact(messageHistory, compactFocus); }
        }
    }

    /** Microcompact：保留最近 KEEP_RECENT 个 tool_result turns，旧的替换为短摘要 */
    @SuppressWarnings("unchecked")
    private static void microcompact(List<Object> history) {
        // history 中元素类型：
        //   String (user text / ack marker)
        //   Message (assistant response)
        //   Map with "blocks" (List<ContentBlockParam>) + "tools" (List<String>) + "ids" (List<String>)
        List<Integer> toolResultTurnIndices = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i) instanceof Map<?,?> map && map.containsKey("tools")) {
                toolResultTurnIndices.add(i);
            }
        }
        if (toolResultTurnIndices.size() <= KEEP_RECENT) return;

        for (int t = 0; t < toolResultTurnIndices.size() - KEEP_RECENT; t++) {
            int idx = toolResultTurnIndices.get(t);
            Map<String,Object> turnInfo = (Map<String,Object>) history.get(idx);
            List<String> toolNames = (List<String>) turnInfo.get("tools");
            List<String> toolUseIds = (List<String>) turnInfo.get("ids");

            // 检查是否所有工具都是保留工具
            boolean allPreserved = true;
            for (String tn : toolNames) {
                if (!PRESERVE_RESULT_TOOLS.contains(tn)) { allPreserved = false; break; }
            }
            if (allPreserved) continue;

            // 重建 blocks：保留的保持原样，不保留的替换为短摘要
            List<ContentBlockParam> originalBlocks = (List<ContentBlockParam>) turnInfo.get("blocks");
            List<ContentBlockParam> compacted = new ArrayList<>();
            int toolIdx = 0;
            for (ContentBlockParam cbp : originalBlocks) {
                if (toolIdx < toolNames.size() && cbp.isToolResult()) {
                    String toolName = toolNames.get(toolIdx);
                    String toolUseId = toolUseIds.get(toolIdx);
                    if (PRESERVE_RESULT_TOOLS.contains(toolName)) {
                        compacted.add(cbp);
                    } else {
                        compacted.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder().toolUseId(toolUseId)
                                .content("[Previous: used " + toolName + "]").build()));
                    }
                    toolIdx++;
                } else {
                    compacted.add(cbp);
                }
            }
            turnInfo.put("blocks", compacted);
        }
    }

    /** 从消息历史重建 paramsBuilder */
    @SuppressWarnings("unchecked")
    private static void rebuildFromHistory(List<Object> history) {
        paramsBuilder = MessageCreateParams.builder().model(modelId).maxTokens(MAX_TOKENS).system(systemPrompt);
        for (Tool t : fullTools) paramsBuilder.addTool(t);
        for (Object entry : history) {
            if (entry instanceof String s) {
                if ("__ack_bg__".equals(s)) { paramsBuilder.addAssistantMessage("Noted background results."); }
                else if ("__ack_inbox__".equals(s)) { paramsBuilder.addAssistantMessage("Noted inbox messages."); }
                else { paramsBuilder.addUserMessage(s); }
            } else if (entry instanceof Message m) {
                paramsBuilder.addMessage(m);
            } else if (entry instanceof Map<?,?> map) {
                List<ContentBlockParam> blocks = (List<ContentBlockParam>) map.get("blocks");
                paramsBuilder.addUserMessageOfBlockParams(blocks);
            }
        }
    }

    // ==================== 工具分发注册 ====================

    private static void registerDispatchers(Map<String, java.util.function.Function<Map<String, Object>, String>> dispatch) {
        // 基础工具（s02）
        dispatch.put("bash", input -> runBash((String) input.get("command"), (String) input.getOrDefault("_tool_use_id", "")));
        dispatch.put("read_file", input -> runRead((String) input.get("path"), input.get("limit") instanceof Number n ? n.intValue() : null, (String) input.getOrDefault("_tool_use_id", "")));
        dispatch.put("write_file", input -> runWrite((String) input.get("path"), (String) input.get("content")));
        dispatch.put("edit_file", input -> runEdit((String) input.get("path"), (String) input.get("old_text"), (String) input.get("new_text")));
        // 待办清单（s03）
        dispatch.put("TodoWrite", input -> { @SuppressWarnings("unchecked") List<?> items = (List<?>) input.get("items"); return todo.update(items); });
        // 子代理（s04）
        dispatch.put("task", input -> runSubagent((String) input.get("prompt"), (String) input.getOrDefault("agent_type", "Explore")));
        // 技能系统（s05）
        dispatch.put("load_skill", input -> skills.load((String) input.get("name")));
        // 手动压缩（s06）
        dispatch.put("compress", input -> "Compressing...");
        // 后台任务（s13）
        dispatch.put("background_run", input -> bg.run((String) input.get("command"), input.get("timeout") instanceof Number n ? n.intValue() : 120));
        dispatch.put("check_background", input -> bg.check((String) input.get("task_id")));
        // 持久化任务板（s12）
        dispatch.put("task_create", input -> createTask((String) input.get("subject"), (String) input.getOrDefault("description","")));
        dispatch.put("task_get", input -> getTask(((Number) input.get("task_id")).intValue()));
        dispatch.put("task_update", input -> { @SuppressWarnings("unchecked") List<Integer> bb = (List<Integer>) input.get("add_blocked_by"); @SuppressWarnings("unchecked") List<Integer> bl = (List<Integer>) input.get("add_blocks"); return updateTask(((Number) input.get("task_id")).intValue(), (String) input.get("status"), bb, bl); });
        dispatch.put("task_list", input -> listTasks());
        // 收件箱（s15）
        dispatch.put("read_inbox", input -> { try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(bus.readInbox("lead")); } catch (Exception e) { return "[]"; } });
        // 自治工具（s17）
        dispatch.put("idle", input -> "Lead does not idle.");
        dispatch.put("claim_task", input -> claimTask(((Number) input.get("task_id")).intValue(), "lead"));
        // 团队协作（s15）
        dispatch.put("spawn_teammate", input -> spawnTeammate((String) input.get("name"), (String) input.get("role"), (String) input.get("prompt")));
        dispatch.put("list_teammates", input -> listAll());
        dispatch.put("send_message", input -> bus.send("lead", (String) input.get("to"), (String) input.get("content"), (String) input.getOrDefault("msg_type","message"), null));
        dispatch.put("broadcast", input -> bus.broadcast("lead", (String) input.get("content"), memberNames()));
        // 协议工具（s16）
        dispatch.put("shutdown_request", input -> handleShutdownRequest((String) input.get("teammate")));
        dispatch.put("plan_approval", input -> handlePlanReview((String) input.get("request_id"), Boolean.TRUE.equals(input.get("approve")), (String) input.getOrDefault("feedback","")));
    }

    // ==================== 23 个工具定义 ====================
    //
    // 工具按来源/功能分组：
    //
    // 【基础工具】(s02) — 文件系统和命令行操作
    //   1. bash          — 执行 shell 命令（OS 自适应、危险命令拦截、超时）
    //   2. read_file     — 读取文件内容（路径沙箱、可选行数限制）
    //   3. write_file    — 写入文件（自动创建父目录、路径沙箱）
    //   4. edit_file     — 精确文本替换（首次匹配、Pattern.quote 字面量）
    //
    // 【Todo 工具】(s03) — 短期任务清单（内存态）
    //   5. TodoWrite     — 更新待办列表（最多 20 项、仅 1 个 in_progress）
    //
    // 【子代理】(s04) — 隔离的一次性任务委派
    //   6. task          — 派生子代理执行探索或通用任务（最多 30 轮）
    //
    // 【技能系统】(s05) — 专业知识加载
    //   7. load_skill    — 从 skills/ 目录加载技能文件（SKILL.md）
    //
    // 【压缩】(s06) — 上下文管理
    //   8. compress      — 手动触发对话压缩（替换历史为摘要）
    //
    // 【后台任务】(s13) — 非阻塞命令执行
    //   9. background_run    — 后台执行命令，立即返回 task_id
    //  10. check_background  — 检查后台任务状态
    //
    // 【持久化任务板】(s12) — 文件系统级任务管理
    //  11. task_create   — 创建持久化任务（.tasks/task_N.json）
    //  12. task_get      — 获取任务详情
    //  13. task_update   — 更新任务状态/依赖（含双向 DAG 维护）
    //  14. task_list     — 列出所有任务
    //
    // 【团队协作】(s15/s16/s17) — 多 Agent 管理
    //  15. spawn_teammate — 生成持久化自治 Teammate（虚拟线程）
    //  16. list_teammates — 列出所有 Teammate 及状态
    //  17. send_message   — 发送消息给 Teammate
    //  18. read_inbox     — 读取并清空 lead 收件箱
    //  19. broadcast      — 广播消息给所有 Teammate
    //
    // 【协议工具】(s16) — Teammate 间握手协议
    //  20. shutdown_request — 请求 Teammate 关闭（request_id 关联）
    //  21. plan_approval    — 审批/拒绝 Teammate 的计划
    //
    // 【自治工具】(s17) — idle 轮询和任务认领
    //  22. idle          — Teammate 声明无更多工作（触发 idle phase）
    //  23. claim_task    — 从任务板认领任务（按角色过滤）

    private static List<Tool> buildFullToolList() {
        return List.of(
                // 1. 执行 shell 命令
                defineTool("bash", "Run a shell command.", Map.of("command", Map.of("type","string")), List.of("command")),
                // 2. 读取文件内容
                defineTool("read_file", "Read file contents.", Map.of("path",Map.of("type","string"),"limit",Map.of("type","integer")), List.of("path")),
                // 3. 写入文件
                defineTool("write_file", "Write content to file.", Map.of("path",Map.of("type","string"),"content",Map.of("type","string")), List.of("path","content")),
                // 4. 精确文本替换
                defineTool("edit_file", "Replace exact text in file.", Map.of("path",Map.of("type","string"),"old_text",Map.of("type","string"),"new_text",Map.of("type","string")), List.of("path","old_text","new_text")),
                // 5. 更新待办清单（s03 内存态）
                defineTool("TodoWrite", "Update task tracking list.", Map.of("items", Map.of("type","array","items",Map.of("type","object","properties",Map.of("content",Map.of("type","string"),"status",Map.of("type","string","enum",List.of("pending","in_progress","completed")),"activeForm",Map.of("type","string")),"required",List.of("content","status","activeForm")))), List.of("items")),
                // 6. 子代理——隔离的一次性任务委派（s04）
                defineTool("task", "Spawn a subagent for isolated exploration or work.", Map.of("prompt",Map.of("type","string"),"agent_type",Map.of("type","string","enum",List.of("Explore","general-purpose"))), List.of("prompt")),
                // 7. 加载技能——专业知识注入（s05）
                defineTool("load_skill", "Load specialized knowledge by name.", Map.of("name",Map.of("type","string")), List.of("name")),
                // 8. 手动触发对话压缩（s06）
                defineTool("compress", "Manually compress conversation context.", Map.of("focus",Map.of("type","string")), null),
                // 9. 后台执行命令（s13）
                defineTool("background_run", "Run command in background thread.", Map.of("command",Map.of("type","string"),"timeout",Map.of("type","integer")), List.of("command")),
                // 10. 检查后台任务状态（s13）
                defineTool("check_background", "Check background task status.", Map.of("task_id",Map.of("type","string")), null),
                // 11. 创建持久化任务（s12 文件系统级）
                defineTool("task_create", "Create a persistent file task.", Map.of("subject",Map.of("type","string"),"description",Map.of("type","string")), List.of("subject")),
                // 12. 获取任务详情（s12）
                defineTool("task_get", "Get task details by ID.", Map.of("task_id",Map.of("type","integer")), List.of("task_id")),
                // 13. 更新任务状态/依赖（s12，含双向 DAG 维护）
                defineTool("task_update", "Update task status or dependencies.", Map.of("task_id",Map.of("type","integer"),"status",Map.of("type","string","enum",List.of("pending","in_progress","completed","deleted")),"add_blocked_by",Map.of("type","array","items",Map.of("type","integer")),"add_blocks",Map.of("type","array","items",Map.of("type","integer"))), List.of("task_id")),
                // 14. 列出所有任务（s12）
                defineTool("task_list", "List all tasks.", Map.of(), null),
                // 15. 生成持久化自治 Teammate（s17）
                defineTool("spawn_teammate", "Spawn a persistent autonomous teammate.", Map.of("name",Map.of("type","string"),"role",Map.of("type","string"),"prompt",Map.of("type","string")), List.of("name","role","prompt")),
                // 16. 列出所有 Teammate（s15）
                defineTool("list_teammates", "List all teammates.", Map.of(), null),
                // 17. 发送消息给 Teammate（s15）
                defineTool("send_message", "Send a message to a teammate.", Map.of("to",Map.of("type","string"),"content",Map.of("type","string"),"msg_type",Map.of("type","string","enum",List.of("message","broadcast","shutdown_request","shutdown_response","plan_approval_response"))), List.of("to","content")),
                // 18. 读取并清空 lead 收件箱（s15）
                defineTool("read_inbox", "Read and drain the lead's inbox.", Map.of(), null),
                // 19. 广播消息给所有 Teammate（s15）
                defineTool("broadcast", "Send message to all teammates.", Map.of("content",Map.of("type","string")), List.of("content")),
                // 20. 请求 Teammate 关闭（s16 shutdown 协议）
                defineTool("shutdown_request", "Request a teammate to shut down.", Map.of("teammate",Map.of("type","string")), List.of("teammate")),
                // 21. 审批/拒绝 Teammate 的计划（s16 plan approval 协议）
                defineTool("plan_approval", "Approve or reject a teammate's plan.", Map.of("request_id",Map.of("type","string"),"approve",Map.of("type","boolean"),"feedback",Map.of("type","string")), List.of("request_id","approve")),
                // 22. Teammate 声明无更多工作（s17 idle phase 触发器）
                defineTool("idle", "Enter idle state.", Map.of(), null),
                // 23. 从任务板认领任务（s17）
                defineTool("claim_task", "Claim a task from the board.", Map.of("task_id",Map.of("type","integer")), List.of("task_id"))
        );
    }

    // ==================== TodoManager (s03) ====================

    static class TodoManager {
        private List<Map<String,String>> items = new ArrayList<>();

        @SuppressWarnings("unchecked")
        String update(List<?> raw) {
            var validated = new ArrayList<Map<String,String>>();
            int ip = 0;
            for (int i = 0; i < raw.size(); i++) {
                Map<String,Object> item = (Map<String,Object>) raw.get(i);
                String content = String.valueOf(item.getOrDefault("content","")).trim();
                String status = String.valueOf(item.getOrDefault("status","pending")).toLowerCase();
                String af = String.valueOf(item.getOrDefault("activeForm","")).trim();
                if (content.isEmpty()) throw new IllegalArgumentException("Item "+i+": content required");
                if (!List.of("pending","in_progress","completed").contains(status)) throw new IllegalArgumentException("Item "+i+": invalid status '"+status+"'");
                if (af.isEmpty()) throw new IllegalArgumentException("Item "+i+": activeForm required");
                if ("in_progress".equals(status)) ip++;
                validated.add(Map.of("content",content,"status",status,"activeForm",af));
            }
            if (validated.size() > 20) throw new IllegalArgumentException("Max 20 todos");
            if (ip > 1) throw new IllegalArgumentException("Only one in_progress allowed");
            items = validated;
            return render();
        }

        String render() {
            if (items.isEmpty()) return "No todos.";
            var lines = new ArrayList<String>();
            for (var item : items) {
                String m = switch(item.get("status")) { case "completed"->"[x]"; case "in_progress"->"[>]"; default->"[ ]"; };
                String suffix = "in_progress".equals(item.get("status")) ? " <- "+item.get("activeForm") : "";
                lines.add(m+" "+item.get("content")+suffix);
            }
            long done = items.stream().filter(i->"completed".equals(i.get("status"))).count();
            lines.add("\n("+done+"/"+items.size()+" completed)");
            return String.join("\n", lines);
        }

        boolean hasOpenItems() { return items.stream().anyMatch(i -> !"completed".equals(i.get("status"))); }
    }

    // ==================== SkillRegistry (s05) ====================

    static class SkillRegistry {
        private final Map<String, Map<String,String>> skills = new LinkedHashMap<>();

        SkillRegistry() {
            if (Files.exists(SKILLS_DIR)) {
                try (var stream = Files.walk(SKILLS_DIR)) {
                    stream.filter(p -> p.getFileName().toString().equals("SKILL.md")).sorted().forEach(p -> {
                        try {
                            String text = Files.readString(p);
                            Map<String,String> meta = new LinkedHashMap<>();
                            String body = text;
                            Matcher m = Pattern.compile("^---\\n(.*?)\\n---\\n(.*)", Pattern.DOTALL).matcher(text);
                            if (m.find()) {
                                for (String line : m.group(1).strip().split("\n")) {
                                    int idx = line.indexOf(':');
                                    if (idx > 0) meta.put(line.substring(0,idx).strip(), line.substring(idx+1).strip());
                                }
                                body = m.group(2).strip();
                            }
                            String name = meta.getOrDefault("name", p.getParent().getFileName().toString());
                            skills.put(name, Map.of("description", meta.getOrDefault("description","-"), "body", body));
                        } catch (IOException ignored) {}
                    });
                } catch (IOException ignored) {}
            }
        }

        String descriptions() {
            if (skills.isEmpty()) return "(no skills)";
            return String.join("\n", skills.entrySet().stream().map(e -> "  - "+e.getKey()+": "+e.getValue().get("description")).toList());
        }

        String load(String name) {
            var s = skills.get(name);
            if (s == null) return "Error: Unknown skill '"+name+"'. Available: "+String.join(", ", skills.keySet());
            return "<skill name=\""+name+"\">\n"+s.get("body")+"\n</skill>";
        }
    }

    // ==================== BackgroundManager (s13) ====================

    static class BackgroundManager {
        private final Map<String, Map<String,Object>> tasks = new ConcurrentHashMap<>();
        private final LinkedBlockingQueue<Map<String,String>> notifications = new LinkedBlockingQueue<>();

        String run(String command, int timeout) {
            String tid = UUID.randomUUID().toString().substring(0,8);
            tasks.put(tid, new ConcurrentHashMap<>(Map.of("status","running","command",command,"result","")));
            Thread.ofVirtual().name("bg-"+tid).start(() -> {
                try {
                    ProcessBuilder pb = System.getProperty("os.name").toLowerCase().contains("win") ? new ProcessBuilder("cmd","/c",command) : new ProcessBuilder("bash","-c",command);
                    pb.directory(WORKDIR.toFile()).redirectErrorStream(true); Process p = pb.start();
                    String o = new String(p.getInputStream().readAllBytes()).trim();
                    if (!p.waitFor(timeout, TimeUnit.SECONDS)) { p.destroyForcibly(); o = "Error: Timeout"; }
                    if (o.isEmpty()) o = "(no output)"; if (o.length()>MAX_OUTPUT) o = o.substring(0,MAX_OUTPUT);
                    tasks.get(tid).putAll(Map.of("status","completed","result",o));
                } catch (Exception e) { tasks.get(tid).putAll(Map.of("status","error","result",e.getMessage())); }
                var t = tasks.get(tid); String preview = (String)t.get("result");
                notifications.offer(Map.of("task_id",tid,"status",(String)t.get("status"),"result",preview!=null?preview.substring(0,Math.min(500,preview.length())):""));
            });
            return "Background task "+tid+" started: "+command.substring(0,Math.min(80,command.length()));
        }

        String check(String tid) {
            if (tid != null) { var t = tasks.get(tid); return t==null ? "Unknown: "+tid : "["+t.get("status")+"] "+t.getOrDefault("result","(running)"); }
            if (tasks.isEmpty()) return "No background tasks.";
            return String.join("\n", tasks.entrySet().stream().map(e -> e.getKey()+": ["+e.getValue().get("status")+"] "+((String)e.getValue().get("command")).substring(0,Math.min(60,((String)e.getValue().get("command")).length()))).toList());
        }

        List<Map<String,String>> drain() { var r = new ArrayList<Map<String,String>>(); while(true) { var n = notifications.poll(); if (n==null) break; r.add(n); } return r; }
    }

    // ==================== MessageBus (s15) ====================

    static class MessageBus {
        MessageBus() { try { Files.createDirectories(INBOX_DIR); } catch (IOException ignored) {} }

        String send(String sender, String to, String content, String msgType, Map<String,Object> extra) {
            if (!VALID_MSG_TYPES.contains(msgType)) return "Error: Invalid msg_type '"+msgType+"'";
            var msg = new LinkedHashMap<String,Object>(); msg.put("type",msgType); msg.put("from",sender); msg.put("content",content); msg.put("timestamp",System.currentTimeMillis()/1000.0);
            if (extra!=null) msg.putAll(extra);
            try (var w = new FileWriter(INBOX_DIR.resolve(to+".jsonl").toFile(), true)) { w.write(MAPPER.writeValueAsString(msg)+"\n"); } catch (IOException ignored) {}
            return "Sent "+msgType+" to "+to;
        }

        @SuppressWarnings("unchecked")
        List<Map<String,Object>> readInbox(String name) {
            Path p = INBOX_DIR.resolve(name+".jsonl"); if (!Files.exists(p)) return List.of();
            try { var lines = Files.readAllLines(p); var msgs = new ArrayList<Map<String,Object>>();
                for (String l : lines) if (!l.isBlank()) try { msgs.add(MAPPER.readValue(l, Map.class)); } catch (Exception ignored) {}
                Files.writeString(p,""); return msgs;
            } catch (IOException e) { return List.of(); }
        }

        String broadcast(String sender, String content, List<String> names) { int c=0; for (String n : names) if (!n.equals(sender)) { send(sender,n,content,"broadcast",null); c++; } return "Broadcast to "+c+" teammates"; }
    }

    // ==================== Subagent (s04) ====================

    private static String runSubagent(String prompt, String agentType) {
        List<Tool> subTools = new ArrayList<>(List.of(
                defineTool("bash","Run command.",Map.of("command",Map.of("type","string")),List.of("command")),
                defineTool("read_file","Read file.",Map.of("path",Map.of("type","string")),List.of("path"))
        ));
        if (!"Explore".equals(agentType)) {
            subTools.add(defineTool("write_file","Write file.",Map.of("path",Map.of("type","string"),"content",Map.of("type","string")),List.of("path","content")));
            subTools.add(defineTool("edit_file","Edit file.",Map.of("path",Map.of("type","string"),"old_text",Map.of("type","string"),"new_text",Map.of("type","string")),List.of("path","old_text","new_text")));
        }

        var subHandlers = new LinkedHashMap<String, java.util.function.Function<Map<String,Object>,String>>();
        subHandlers.put("bash", input -> runBash((String)input.get("command"), ""));
        subHandlers.put("read_file", input -> runRead((String)input.get("path"), null, ""));
        subHandlers.put("write_file", input -> runWrite((String)input.get("path"), (String)input.get("content")));
        subHandlers.put("edit_file", input -> runEdit((String)input.get("path"), (String)input.get("old_text"), (String)input.get("new_text")));

        var subBuilder = MessageCreateParams.builder().model(modelId).maxTokens(8000);
        for (Tool t : subTools) subBuilder.addTool(t);
        subBuilder.addUserMessage(prompt);

        for (int round = 0; round < 30; round++) {
            Message resp; try { resp = client.messages().create(subBuilder.build()); } catch (Exception e) { return "(subagent failed: "+e.getMessage()+")"; }
            subBuilder.addMessage(resp);
            if (!resp.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                var texts = new ArrayList<String>(); for (ContentBlock b : resp.content()) b.text().ifPresent(tb -> texts.add(tb.text()));
                return texts.isEmpty() ? "(no summary)" : String.join("", texts);
            }
            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock b : resp.content()) {
                if (!b.isToolUse()) continue; ToolUseBlock tu = b.asToolUse();
                @SuppressWarnings("unchecked") Map<String,Object> in = (Map<String,Object>) jsonValueToObject(tu._input());
                var h = subHandlers.get(tu.name()); String out; try { out = h!=null ? h.apply(in!=null?in:Map.of()) : "Unknown"; } catch (Exception e) { out = "Error: "+e.getMessage(); }
                if (out.length()>MAX_OUTPUT) out = out.substring(0,MAX_OUTPUT);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(out).build()));
            }
            subBuilder.addUserMessageOfBlockParams(results);
        }
        return "(subagent reached max rounds)";
    }

    // ==================== Compression（s06 上下文压缩） ====================
    //
    // 压缩流程：
    // 1. 将当前对话日志保存到 .transcripts/ 目录（可追溯）
    // 2. 截取最后 80000 字符，调用 LLM 生成结构化摘要
    // 3. 用摘要重建 paramsBuilder（替换整个历史）
    // 4. 重置 token 估算计数器

    private static void doAutoCompact(List<Object> history, String focus) {
        try {
            Files.createDirectories(TRANSCRIPT_DIR);
            Path transcriptPath = TRANSCRIPT_DIR.resolve("transcript_"+System.currentTimeMillis()+".jsonl");
            Files.writeString(transcriptPath, String.join("\n", conversationLog));

            String convText = String.join("\n", conversationLog);
            if (convText.length() > 80000) convText = convText.substring(convText.length()-80000);

            String summaryPrompt = "Summarize this conversation for continuity. Structure:\n1) Task overview\n2) Current state\n3) Key decisions\n4) Next steps\n5) Context to preserve\nBe concise.\n";
            if (focus != null && !focus.isBlank()) summaryPrompt += "Pay special attention to: " + focus + "\n";
            summaryPrompt += "\n" + convText;

            Message summaryResp = client.messages().create(MessageCreateParams.builder()
                    .model(modelId).maxTokens(4000)
                    .addUserMessage(summaryPrompt)
                    .build());

            var summarySb = new StringBuilder();
            for (ContentBlock b : summaryResp.content()) b.text().ifPresent(summarySb::append);
            String summary = summarySb.toString();

            String continuation = "This session is continued from a previous conversation.\n\n"+summary+"\n\nPlease continue without asking further questions.";

            // 用摘要重建历史
            history.clear();
            history.add(continuation);

            paramsBuilder = MessageCreateParams.builder().model(modelId).maxTokens(MAX_TOKENS).system(systemPrompt);
            for (Tool t : fullTools) paramsBuilder.addTool(t);
            paramsBuilder.addUserMessage(continuation);

            tokenEstimate = 0; conversationLog.clear(); conversationLog.add("[compressed] "+summary);
            System.out.println(ANSI_DIM+"[auto-compact triggered]"+ANSI_RESET);
        } catch (Exception e) { System.err.println("Compression error: "+e.getMessage()); }
    }

    // ==================== Task Management (s12) ====================

    private static int maxTaskId() {
        try (var s = Files.list(TASKS_DIR)) { return s.filter(p->p.getFileName().toString().matches("task_\\d+\\.json")).mapToInt(p->{String n=p.getFileName().toString(); return Integer.parseInt(n.substring(5,n.length()-5));}).max().orElse(0); }
        catch (IOException e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> loadTask(int id) {
        Path p = TASKS_DIR.resolve("task_"+id+".json"); if (!Files.exists(p)) throw new IllegalArgumentException("Task "+id+" not found");
        try { return MAPPER.readValue(Files.readString(p), Map.class); } catch (IOException e) { throw new RuntimeException(e); }
    }

    private static void saveTask(Map<String,Object> task) { try { Files.writeString(TASKS_DIR.resolve("task_"+task.get("id")+".json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task)); } catch (IOException e) { throw new RuntimeException(e); } }

    private static synchronized String createTask(String subject, String description) {
        var task = new LinkedHashMap<String,Object>(); task.put("id",nextTaskId); task.put("subject",subject); task.put("description",description!=null?description:""); task.put("status","pending"); task.put("owner",""); task.put("blockedBy",new ArrayList<Integer>()); task.put("blocks",new ArrayList<Integer>());
        saveTask(task); nextTaskId++; try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task); } catch (IOException e) { return task.toString(); }
    }

    private static String getTask(int id) { try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(loadTask(id)); } catch (IOException e) { return "Error: "+e.getMessage(); } }

    @SuppressWarnings("unchecked")
    private static synchronized String updateTask(int id, String status, List<Integer> addBlockedBy, List<Integer> addBlocks) {
        var task = loadTask(id);
        if (status!=null) { task.put("status",status);
            if ("completed".equals(status)) clearDependency(id);
            if ("deleted".equals(status)) { try { Files.deleteIfExists(TASKS_DIR.resolve("task_"+id+".json")); return "Task "+id+" deleted"; } catch (IOException e) { return "Error: "+e.getMessage(); } }
        }
        if (addBlockedBy!=null) { var set = new LinkedHashSet<>((List<Integer>)task.getOrDefault("blockedBy",new ArrayList<>())); set.addAll(addBlockedBy); task.put("blockedBy",new ArrayList<>(set)); }
        if (addBlocks!=null) { var set = new LinkedHashSet<>((List<Integer>)task.getOrDefault("blocks",new ArrayList<>())); set.addAll(addBlocks); task.put("blocks",new ArrayList<>(set)); }
        saveTask(task); try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task); } catch (IOException e) { return task.toString(); }
    }

    private static synchronized String claimTask(int id, String owner) { var t=loadTask(id); t.put("owner",owner); t.put("status","in_progress"); saveTask(t); return "Claimed task #"+id+" for "+owner; }

    private static String listTasks() {
        try (var s = Files.list(TASKS_DIR)) {
            var tasks = s.filter(p->p.getFileName().toString().matches("task_\\d+\\.json")).sorted().map(p->{try{return MAPPER.readValue(Files.readString(p),Map.class);}catch(Exception e){return null;}}).filter(Objects::nonNull).toList();
            if (tasks.isEmpty()) return "No tasks."; var lines = new ArrayList<String>();
            for (var t : tasks) { String m = switch((String)t.getOrDefault("status","?")){case "pending"->"[ ]";case "in_progress"->"[>]";case "completed"->"[x]";default->"[?]";}; String o = t.get("owner")!=null && !t.get("owner").toString().isEmpty() ? " @"+t.get("owner") : ""; @SuppressWarnings("unchecked") var bb=(List<Integer>)t.getOrDefault("blockedBy",List.of()); String blocked=!bb.isEmpty()?" (blocked by: "+bb+")":""; lines.add(m+" #"+t.get("id")+": "+t.get("subject")+o+blocked); }
            return String.join("\n",lines);
        } catch (IOException e) { return "Error: "+e.getMessage(); }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> scanUnclaimedTasks() {
        try (var s = Files.list(TASKS_DIR)) { var r = new ArrayList<Map<String,Object>>();
            for (var p : s.filter(p->p.getFileName().toString().matches("task_\\d+\\.json")).sorted().toList()) {
                try { var t = (Map<String,Object>)MAPPER.readValue(Files.readString(p),Map.class);
                    if ("pending".equals(t.get("status")) && (t.get("owner")==null||t.get("owner").toString().isEmpty()) && ((List<?>)t.getOrDefault("blockedBy",List.of())).isEmpty()) r.add(t);
                } catch (IOException ignored) {}
            } return r;
        } catch (IOException e) { return List.of(); }
    }

    @SuppressWarnings("unchecked")
    private static void clearDependency(int completedId) {
        try (var s = Files.list(TASKS_DIR)) { s.filter(p->p.getFileName().toString().matches("task_\\d+\\.json")).forEach(p->{try{var t=MAPPER.readValue(Files.readString(p),Map.class);List<Integer>bb=(List<Integer>)t.getOrDefault("blockedBy",new ArrayList<>());if(bb.contains(completedId)){bb=new ArrayList<>(bb);bb.remove(Integer.valueOf(completedId));t.put("blockedBy",bb);saveTask(t);}}catch(IOException ignored){}}); } catch (IOException ignored) {}
    }

    // ==================== 团队管理 (s15/s16/s17) ====================

    @SuppressWarnings("unchecked")
    private static Map<String,Object> loadTeamConfig() {
        try { Files.createDirectories(TEAM_DIR); } catch (IOException ignored) {}
        if (Files.exists(CONFIG_PATH)) { try { return MAPPER.readValue(Files.readString(CONFIG_PATH), Map.class); } catch (IOException ignored) {} }
        var cfg = new LinkedHashMap<String,Object>(); cfg.put("team_name","default"); cfg.put("members",new ArrayList<>()); return cfg;
    }

    private static synchronized void saveTeamConfig() { try { Files.writeString(CONFIG_PATH, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(teamConfig)); } catch (IOException ignored) {} }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> findMember(String name) { return ((List<Map<String,Object>>)teamConfig.get("members")).stream().filter(m->name.equals(m.get("name"))).findFirst().orElse(null); }

    @SuppressWarnings("unchecked")
    private static String listAll() { var members=(List<Map<String,Object>>)teamConfig.get("members"); if(members.isEmpty()) return "No teammates."; var lines=new ArrayList<String>(); lines.add("Team: "+teamConfig.get("team_name")); for(var m:members) lines.add("  "+m.get("name")+" ("+m.get("role")+"): "+m.get("status")); return String.join("\n",lines); }

    @SuppressWarnings("unchecked")
    private static List<String> memberNames() { return ((List<Map<String,Object>>)teamConfig.get("members")).stream().map(m->(String)m.get("name")).toList(); }

    private static synchronized void setMemberStatus(String name, String status) { var m=findMember(name); if(m!=null){m.put("status",status);saveTeamConfig();} }

    @SuppressWarnings("unchecked")
    private static synchronized String spawnTeammate(String name, String role, String prompt) {
        var member = findMember(name);
        if (member!=null) { String s=(String)member.get("status"); if(!"idle".equals(s)&&!"shutdown".equals(s)) return "Error: '"+name+"' is currently "+s; member.put("status","working"); member.put("role",role); }
        else { member=new LinkedHashMap<>(Map.of("name",name,"role",role,"status","working")); ((List<Map<String,Object>>)teamConfig.get("members")).add(member); }
        saveTeamConfig(); Thread.ofVirtual().name("agent-"+name).start(()->teammateLoop(name,role,prompt));
        return "Spawned '"+name+"' (role: "+role+")";
    }

    /**
     * 持久化 Teammate 工作循环（s17 自治生命周期）。
     * <p>
     * 两阶段循环：
     * <pre>
     * while (true):
     *   WORK PHASE:  标准 agent 循环（最多 50 轮），LLM 自然停止或调用 idle → 退出
     *   IDLE PHASE:  轮询收件箱和未认领任务（每 5 秒，最多 60 秒）
     *     ├─ 收件箱有消息 → 恢复 WORK
     *     ├─ 有未认领任务 → 自动认领 → 恢复 WORK
     *     └─ 超时 → shutdown（退出循环）
     * </pre>
     */
    private static void teammateLoop(String name, String role, String prompt) {
        String teamName = (String)teamConfig.getOrDefault("team_name","default");
        var params = MessageCreateParams.builder().model(modelId).maxTokens(MAX_TOKENS).system("You are '"+name+"', role: "+role+", team: "+teamName+", at "+WORKDIR+". Use idle when done. You may auto-claim tasks.");

        List<Tool> tools = List.of(
                defineTool("bash","Run command.",Map.of("command",Map.of("type","string")),List.of("command")),
                defineTool("read_file","Read file.",Map.of("path",Map.of("type","string")),List.of("path")),
                defineTool("write_file","Write file.",Map.of("path",Map.of("type","string"),"content",Map.of("type","string")),List.of("path","content")),
                defineTool("edit_file","Edit file.",Map.of("path",Map.of("type","string"),"old_text",Map.of("type","string"),"new_text",Map.of("type","string")),List.of("path","old_text","new_text")),
                defineTool("send_message","Send message.",Map.of("to",Map.of("type","string"),"content",Map.of("type","string")),List.of("to","content")),
                defineTool("idle","Signal no more work.",Map.of(),null),
                defineTool("claim_task","Claim task.",Map.of("task_id",Map.of("type","integer")),List.of("task_id"))
        );
        for (Tool t : tools) params.addTool(t);
        params.addUserMessage(prompt);

        var disp = new LinkedHashMap<String,java.util.function.Function<Map<String,Object>,String>>();
        disp.put("bash", input -> runBash((String)input.get("command"), (String)input.getOrDefault("_tool_use_id","")));
        disp.put("read_file", input -> runRead((String)input.get("path"),null, (String)input.getOrDefault("_tool_use_id","")));
        disp.put("write_file", input -> runWrite((String)input.get("path"),(String)input.get("content")));
        disp.put("edit_file", input -> runEdit((String)input.get("path"),(String)input.get("old_text"),(String)input.get("new_text")));
        disp.put("send_message", input -> bus.send(name,(String)input.get("to"),(String)input.get("content"),"message",null));
        disp.put("claim_task", input -> claimTask(((Number)input.get("task_id")).intValue(),name));

        while (true) {
            boolean idleRequested = false;
            for (int round=0; round<50; round++) {
                var inbox = bus.readInbox(name);
                for (var msg : inbox) { if("shutdown_request".equals(msg.get("type"))){setMemberStatus(name,"shutdown");return;} try{params.addUserMessage(MAPPER.writeValueAsString(msg));}catch(Exception ignored){} }
                try {
                    Message resp = client.messages().create(params.build()); params.addMessage(resp);
                    if (!resp.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) break;
                    List<ContentBlockParam> results = new ArrayList<>();
                    for (ContentBlock block : resp.content()) {
                        if (!block.isToolUse()) continue; ToolUseBlock tu = block.asToolUse();
                        if ("idle".equals(tu.name())) { idleRequested=true; String out="Entering idle phase."; System.out.println(ANSI_DIM+"  ["+name+"] idle: "+out+ANSI_RESET); results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(out).build())); continue; }
                        @SuppressWarnings("unchecked") Map<String,Object> in = (Map<String,Object>)jsonValueToObject(tu._input());
                        if (in==null) in=new LinkedHashMap<>(); else if (!(in instanceof LinkedHashMap)) in=new LinkedHashMap<>(in);
                        in.put("_tool_use_id", tu.id());
                        var h=disp.get(tu.name()); String out; try{out=h!=null?h.apply(in):"Unknown";}catch(Exception e){out="Error: "+e.getMessage();}
                        System.out.println(ANSI_DIM+"  ["+name+"] "+tu.name()+": "+out.substring(0,Math.min(120,out.length()))+ANSI_RESET);
                        results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(tu.id()).content(out).build()));
                    }
                    params.addUserMessageOfBlockParams(results);
                    if (idleRequested) break;
                } catch (Exception e) { System.out.println(ANSI_DIM+"  ["+name+"] error: "+e.getMessage()+ANSI_RESET); setMemberStatus(name,"shutdown"); return; }
            }

            setMemberStatus(name,"idle"); boolean resume=false; int polls=IDLE_TIMEOUT_SECONDS/Math.max(POLL_INTERVAL_SECONDS,1);
            for (int p=0; p<polls; p++) {
                try{Thread.sleep(POLL_INTERVAL_SECONDS*1000L);}catch(InterruptedException e){Thread.currentThread().interrupt();setMemberStatus(name,"shutdown");return;}
                var inbox=bus.readInbox(name); if(!inbox.isEmpty()){for(var msg:inbox){if("shutdown_request".equals(msg.get("type"))){setMemberStatus(name,"shutdown");return;}try{params.addUserMessage(MAPPER.writeValueAsString(msg));}catch(Exception ignored){}} resume=true; break;}
                var unclaimed=scanUnclaimedTasks(); if(!unclaimed.isEmpty()){var task=unclaimed.get(0);int tid=((Number)task.get("id")).intValue();claimTask(tid,name);
                    params.addUserMessage("<identity>You are '"+name+"', role: "+role+", team: "+teamName+".</identity>");params.addAssistantMessage("I am "+name+". Continuing.");
                    params.addUserMessage("<auto-claimed>Task #"+task.get("id")+": "+task.get("subject")+"\n"+task.getOrDefault("description","")+"</auto-claimed>");params.addAssistantMessage("Claimed task #"+tid+". Working on it."); resume=true; break;}
            }
            if(!resume){setMemberStatus(name,"shutdown");return;} setMemberStatus(name,"working");
        }
    }

    // ==================== 协议处理器 (s16) ====================

    private static String handleShutdownRequest(String teammate) { String rid=UUID.randomUUID().toString().substring(0,8); shutdownRequests.put(rid,new ConcurrentHashMap<>(Map.of("target",teammate,"status","pending"))); bus.send("lead",teammate,"Please shut down.","shutdown_request",Map.of("request_id",rid)); return "Shutdown request "+rid+" sent to '"+teammate+"'"; }
    private static String handlePlanReview(String rid, boolean approve, String feedback) { var req=planRequests.get(rid); if(req==null) return "Error: Unknown plan request_id '"+rid+"'"; req.put("status",approve?"approved":"rejected"); bus.send("lead",(String)req.get("from"),feedback!=null?feedback:"","plan_approval_response",Map.of("request_id",rid,"approve",approve,"feedback",feedback!=null?feedback:"")); return "Plan "+req.get("status")+" for '"+req.get("from")+"'"; }

    // ==================== 基础设施 ====================

    private static AnthropicClient buildClient(String apiKey, String baseUrl) {
        if (baseUrl!=null && !baseUrl.isBlank()) return AnthropicOkHttpClient.builder().apiKey(apiKey).baseUrl(baseUrl).build();
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    private static Tool defineTool(String name, String desc, Map<String,Object> props, List<String> req) {
        var sb = Tool.InputSchema.builder().properties(JsonValue.from(props));
        if (req!=null && !req.isEmpty()) sb.putAdditionalProperty("required", JsonValue.from(req));
        return Tool.builder().name(name).description(desc).inputSchema(sb.build()).build();
    }

    private static Path safePath(String p) { Path r=WORKDIR.resolve(p).normalize().toAbsolutePath(); if(!r.startsWith(WORKDIR)) throw new IllegalArgumentException("Path escapes workspace: "+p); return r; }

    private static String runBash(String cmd, String toolUseId) {
        if (cmd==null||cmd.isBlank()) return "Error: command required";
        for (String d: List.of("rm -rf /","sudo","shutdown","reboot","> /dev/")) if (cmd.contains(d)) return "Error: Dangerous command blocked";
        try { ProcessBuilder pb=System.getProperty("os.name").toLowerCase().contains("win") ? new ProcessBuilder("cmd","/c",cmd) : new ProcessBuilder("bash","-c",cmd);
            pb.directory(WORKDIR.toFile()).redirectErrorStream(true); Process p=pb.start();
            String o=new String(p.getInputStream().readAllBytes()).trim(); if (!p.waitFor(BASH_TIMEOUT_SECONDS,TimeUnit.SECONDS)){p.destroyForcibly();return "Error: Timeout";}
            if (o.isEmpty()) return "(no output)"; if (o.length()>MAX_OUTPUT) o=o.substring(0,MAX_OUTPUT);
            return maybePersistOutput(toolUseId, o, PERSIST_TRIGGER_BASH);
        } catch (Exception e) { return "Error: "+e.getMessage(); }
    }

    private static String runRead(String path, Integer limit, String toolUseId) {
        try { var lines=Files.readAllLines(safePath(path)); if (limit!=null && limit<lines.size()) { lines=new ArrayList<>(lines.subList(0,limit)); lines.add("... ("+(lines.size()-limit)+" more)"); }
            String r=String.join("\n",lines); if (r.length()>MAX_OUTPUT) r=r.substring(0,MAX_OUTPUT);
            return maybePersistOutput(toolUseId, r, PERSIST_TRIGGER_DEFAULT);
        } catch (Exception e) { return "Error: "+e.getMessage(); }
    }

    private static String runWrite(String path, String content) { try { Path fp=safePath(path); Files.createDirectories(fp.getParent()); Files.writeString(fp,content); return "Wrote "+content.length()+" bytes to "+path; } catch (Exception e) { return "Error: "+e.getMessage(); } }

    private static String runEdit(String path, String oldT, String newT) { try { Path fp=safePath(path); String c=Files.readString(fp); if (!c.contains(oldT)) return "Error: Text not found"; int idx=c.indexOf(oldT); Files.writeString(fp,c.substring(0,idx)+newT+c.substring(idx+oldT.length())); return "Edited "+path; } catch (Exception e) { return "Error: "+e.getMessage(); } }

    // ---- Persisted Output ----

    private static String formatSize(int size) {
        if (size < 1024) return size + "B";
        if (size < 1024*1024) return String.format("%.1fKB", size/1024.0);
        return String.format("%.1fMB", size/(1024.0*1024));
    }

    private static String previewSlice(String text, int limit) {
        if (text.length() <= limit) return text;
        int cut = text.lastIndexOf('\n', limit);
        if (cut < limit / 2) cut = limit;
        return text.substring(0, cut) + "\n... (" + formatSize(text.length()) + " total, truncated)";
    }

    private static Path persistToolResult(String toolUseId, String content) {
        try {
            Files.createDirectories(TOOL_RESULTS_DIR);
            String filename = toolUseId.replaceAll("[^a-zA-Z0-9_-]", "_") + ".txt";
            Path stored = TOOL_RESULTS_DIR.resolve(filename);
            Files.writeString(stored, content);
            return stored;
        } catch (Exception e) { return null; }
    }

    private static String buildPersistedMarker(Path storedPath, String content) {
        String relPath = WORKDIR.relativize(storedPath).toString().replace('\\', '/');
        String preview = previewSlice(content, PERSISTED_PREVIEW_CHARS);
        return "<persisted-output path=\"" + relPath + "\" size=\"" + formatSize(content.length()) + "\">\n"
                + preview + "\n</persisted-output>";
    }

    private static String maybePersistOutput(String toolUseId, String output, int triggerChars) {
        if (toolUseId == null || toolUseId.isEmpty() || output.length() <= triggerChars) return output;
        Path stored = persistToolResult(toolUseId, output);
        if (stored == null) return output;
        return buildPersistedMarker(stored, output);
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
