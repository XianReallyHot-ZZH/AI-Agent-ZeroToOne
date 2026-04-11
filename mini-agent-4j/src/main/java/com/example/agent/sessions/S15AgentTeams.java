package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * S15：多 Agent 团队 —— 持久化命名 Agent + 消息总线通信（自包含实现）。
 * <p>
 * 本文件完全自包含——不依赖 com.example.agent.* 下的任何共享包。
 * 所有基础设施（客户端构建、工具定义、bash/read/write/edit 执行、
 * 消息总线、队友管理器、ANSI 颜色输出）全部内联实现。
 * <p>
 * 这是 S09（多 Agent 团队）的自包含重写版。S09 依赖 MessageBus、
 * TeamManager 等共享模块，而 S15 将这些全部内联。
 * <p>
 * 架构概览：
 * <pre>
 * Lead Agent (REPL, 主线程)
 *   ├── spawn_teammate → alice (Virtual Thread, 独立 Agent 循环)
 *   ├── spawn_teammate → bob   (Virtual Thread, 独立 Agent 循环)
 *   └── MessageBus (.team/inbox/*.jsonl, 文件级消息传递)
 *         ├── send_message(lead → alice): 追加一行 JSON 到 alice.jsonl
 *         ├── send_message(alice → bob):  追加一行 JSON 到 bob.jsonl
 *         └── broadcast(lead → all):      向所有非 lead 的 teammate 发送
 * </pre>
 * <p>
 * 与 Subagent（S04）的关键区别：
 * <pre>
 * Subagent:  spawn → execute → return → destroyed    （无状态，一次性）
 * Teammate:  spawn → work → idle → work → shutdown   （有状态，持久化）
 * </pre>
 * <p>
 * Teammate 简化版生命周期（不含 idle 轮询和任务认领，那是 S17 的内容）：
 * <pre>
 *   WORK PHASE: agent loop (最多 50 轮), LLM 自然停止（END_TURN）→ 退出工作阶段
 *   完成后设为 idle 状态，可被 respawn 重新激活
 * </pre>
 * <p>
 * 与 S17 的关键区别：S15 没有 idle 工具和 idle 轮询阶段。
 * Teammate 在工作阶段自然结束后直接 idle，不会自动寻找新任务。
 * 这简化了生命周期，但代价是 Teammate 必须被 Lead 显式 respawn
 * 才能再次工作。S17 通过 idle polling + 任务认领解决了这个问题。
 * <p>
 * REPL 命令：/team（查看团队状态）, /inbox（查看 lead 收件箱）
 * <p>
 * Lead 工具（9 个）：bash, read_file, write_file, edit_file,
 *   spawn_teammate, list_teammates, send_message, read_inbox, broadcast
 * <p>
 * Teammate 工具（6 个）：bash, read_file, write_file, edit_file,
 *   send_message, read_inbox
 * <p>
 * 关键洞察："Teammate 是有名字的、有记忆的、可以随时对话的长期合作者。"
 * <p>
 * 外部依赖仅有：
 * <ul>
 *   <li>com.anthropic.* — Anthropic Java SDK</li>
 *   <li>io.github.cdimascio.dotenv.* — dotenv-java 环境变量加载</li>
 *   <li>com.fasterxml.jackson.databind.ObjectMapper — JSON 序列化</li>
 *   <li>java.* — JDK 标准库</li>
 * </ul>
 * <p>
 * 对应 Python 原版：s15_agent_teams.py
 *
 * @see S01AgentLoop 自包含实现的风格参考
 */
public class S15AgentTeams {

    // ==================== 常量定义 ====================

    /** 工作目录 —— Agent 的文件系统沙箱根目录 */
    private static final Path WORKDIR = Path.of(System.getProperty("user.dir")).toAbsolutePath();

    /** 最大输出 token 数 */
    private static final long MAX_TOKENS = 8000;

    /** Bash 命令执行超时时间（秒） */
    private static final int BASH_TIMEOUT_SECONDS = 120;

    /** Bash 输出截断上限（字符数） */
    private static final int MAX_OUTPUT = 50000;

    /** 工具结果预览打印长度 */
    private static final int PREVIEW_LEN = 200;

    /** Teammate 工作阶段最大循环轮次 */
    private static final int MAX_WORK_ROUNDS = 50;

    /** 危险命令黑名单 */
    private static final List<String> DANGEROUS_COMMANDS = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    /** 有效消息类型集合 */
    private static final Set<String> VALID_MSG_TYPES = Set.of(
            "message", "broadcast", "shutdown_request",
            "shutdown_response", "plan_approval_response"
    );

    /** ANSI 重置码 */
    private static final String ANSI_RESET = "\033[0m";
    /** ANSI 黄色（打印执行的命令） */
    private static final String ANSI_YELLOW = "\033[33m";
    /** ANSI 青色（REPL 提示符） */
    private static final String ANSI_CYAN = "\033[36m";
    /** ANSI 灰色/暗色（teammate 工具输出） */
    private static final String ANSI_DIM = "\033[2m";
    /** ANSI 红色（错误信息） */
    private static final String ANSI_RED = "\033[31m";

    /** JSON 序列化/反序列化工具 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 共享状态 ====================

    /** Anthropic API 客户端（主线程和 teammate 共享） */
    private static AnthropicClient client;

    /** 模型 ID（主线程和 teammate 共享） */
    private static String model;

    /** 消息总线实例（所有 Agent 共享） */
    private static MessageBus bus;

    /** 队友管理器实例 */
    private static TeammateManager teamMgr;

    // ==================== 主入口 ====================

    /**
     * 自包含的 S15 Agent Teams 主方法。
     * <p>
     * 运行流程：
     * <ol>
     *   <li>加载 .env 环境变量（API Key、模型 ID 等）</li>
     *   <li>构建 Anthropic API 客户端</li>
     *   <li>初始化消息总线和队友管理器</li>
     *   <li>定义 Lead 的 9 个工具</li>
     *   <li>进入 REPL 主循环：读取用户输入 → Lead Agent 循环 → 输出结果</li>
     * </ol>
     */
    public static void main(String[] args) {
        // ---- 1. 加载环境变量 ----
        Dotenv dotenv = Dotenv.configure()
                .directory(WORKDIR.toString())
                .ignoreIfMissing()
                .load();

        // 如果设置了自定义 Base URL（第三方兼容端点），清除认证令牌避免冲突
        String baseUrl = dotenv.get("ANTHROPIC_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) {
            System.clearProperty("ANTHROPIC_AUTH_TOKEN");
        }

        // ---- 2. 读取必要配置 ----
        String apiKey = dotenv.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY 未配置。请在 .env 文件或系统环境变量中设置。");
        }

        model = dotenv.get("MODEL_ID");
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "MODEL_ID 未配置。请在 .env 文件或系统环境变量中设置。");
        }

        // ---- 3. 构建客户端 ----
        client = buildClient(apiKey, baseUrl);

        // ---- 4. 初始化消息总线和队友管理器 ----
        Path teamDir = WORKDIR.resolve(".team");
        Path inboxDir = teamDir.resolve("inbox");
        bus = new MessageBus(inboxDir);
        teamMgr = new TeammateManager(teamDir, bus);

        // ---- 5. 系统提示词 ----
        // 告诉 Lead 它是团队领导，负责生成队友和通过收件箱通信
        String systemPrompt = "You are a team lead at " + WORKDIR
                + ". Spawn teammates and communicate via inboxes.";

        // ---- 6. Lead 工具定义（9 个） ----
        // bash/read/write/edit 四个基础工具 + 5 个团队工具
        List<Tool> tools = List.of(
                // ---- 基础工具（4 个） ----
                defineTool("bash", "Run a shell command in the current workspace.",
                        Map.of("command", Map.of("type", "string")),
                        List.of("command")),
                defineTool("read_file", "Read file contents.",
                        Map.of("path", Map.of("type", "string"),
                                "limit", Map.of("type", "integer")),
                        List.of("path")),
                defineTool("write_file", "Write content to file.",
                        Map.of("path", Map.of("type", "string"),
                                "content", Map.of("type", "string")),
                        List.of("path", "content")),
                defineTool("edit_file", "Replace exact text in file.",
                        Map.of("path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),
                // ---- 团队工具（5 个） ----
                defineTool("spawn_teammate",
                        "Spawn a persistent teammate (runs in background virtual thread).",
                        Map.of("name", Map.of("type", "string"),
                                "role", Map.of("type", "string"),
                                "prompt", Map.of("type", "string")),
                        List.of("name", "role", "prompt")),
                defineTool("list_teammates", "List all teammates and their status.",
                        Map.of(), null),
                defineTool("send_message", "Send a message to a teammate's inbox.",
                        Map.of("to", Map.of("type", "string"),
                                "content", Map.of("type", "string"),
                                "msg_type", Map.of("type", "string",
                                        "enum", List.of("message", "broadcast",
                                                "shutdown_request", "shutdown_response",
                                                "plan_approval_response"))),
                        List.of("to", "content")),
                defineTool("read_inbox", "Read and drain the lead's inbox.",
                        Map.of(), null),
                defineTool("broadcast", "Broadcast a message to all teammates.",
                        Map.of("content", Map.of("type", "string")),
                        List.of("content"))
        );

        // ---- 7. 构建初始参数 ----
        // MessageCreateParams.Builder 贯穿整个 REPL 生命周期
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(systemPrompt);
        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }

        // ---- 8. REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println("=== S15 Agent Teams (self-contained) ===");
        System.out.println("Commands: /team, /inbox | q/exit to quit");
        System.out.println();

        while (true) {
            // 打印青色提示符
            System.out.print(printCyan("s15 >> "));

            // 处理 EOF（Ctrl+D / Ctrl+C）
            if (!scanner.hasNextLine()) {
                break;
            }

            String query = scanner.nextLine().trim();

            // 退出命令
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // ---- REPL 斜杠命令 ----
            // /team: 查看当前团队所有成员及其状态
            if ("/team".equals(query)) {
                System.out.println(teamMgr.listAll());
                continue;
            }
            // /inbox: 查看并排空 lead 的收件箱
            if ("/inbox".equals(query)) {
                try {
                    List<Map<String, Object>> msgs = bus.readInbox("lead");
                    System.out.println(msgs.isEmpty() ? "Inbox empty."
                            : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(msgs));
                } catch (Exception e) {
                    System.out.println("Inbox empty.");
                }
                continue;
            }

            // 追加用户消息到对话历史
            paramsBuilder.addUserMessage(query);

            // 执行 Lead Agent 循环
            try {
                agentLoop(paramsBuilder, tools);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }

            // 每轮结束后空一行，改善可读性
            System.out.println();
        }

        System.out.println("Bye!");
    }

    // ==================== Lead Agent 核心循环 ====================

    /**
     * Lead Agent 核心循环 —— 带收件箱注入的 Agent 循环。
     * <p>
     * 与 S01 的 agentLoop 相比，S15 增加了两个关键机制：
     * <ol>
     *   <li><b>收件箱注入</b>：每轮 LLM 调用前，先排空 lead 的收件箱，
     *       将未读消息以 {@code <inbox>} 标签注入到对话历史中。
     *       这让 Lead 能实时感知 teammate 的汇报和请求。</li>
     *   <li><b>团队工具分发</b>：在基础工具之上，增加了 spawn_teammate、
     *       send_message 等团队协作工具的分发逻辑。</li>
     * </ol>
     *
     * @param paramsBuilder 消息参数构建器（对话历史在此累积）
     * @param tools         Lead 工具列表（用于引用，实际分发在方法内）
     */
    private static void agentLoop(MessageCreateParams.Builder paramsBuilder,
                                  List<Tool> tools) {
        while (true) {
            // ---- 0. 排空 lead 收件箱，注入到对话历史 ----
            // 这是团队通信的关键：每轮 LLM 调用前读取 lead 的收件箱，
            // 将 teammate 发来的消息作为上下文注入
            List<Map<String, Object>> inboxMsgs = bus.readInbox("lead");
            if (!inboxMsgs.isEmpty()) {
                try {
                    String inboxJson = MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(inboxMsgs);
                    // 用 <inbox> 标签包裹，方便 LLM 识别消息来源
                    paramsBuilder.addUserMessage(
                            "<inbox>" + inboxJson + "</inbox>");
                } catch (Exception ignored) {
                    // 序列化失败时静默跳过，不影响主流程
                }
            }

            // ---- 1. 调用 LLM ----
            Message response = client.messages().create(paramsBuilder.build());

            // ---- 2. 将 assistant 回复追加到对话历史 ----
            paramsBuilder.addMessage(response);

            // ---- 3. 检查停止原因 ----
            boolean isToolUse = response.stopReason()
                    .map(StopReason.TOOL_USE::equals)
                    .orElse(false);

            if (!isToolUse) {
                // 模型决定停止对话，打印文本回复
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(textBlock ->
                            System.out.println(textBlock.text()));
                }
                return; // 回到 REPL 等待下一个用户输入
            }

            // ---- 4. 遍历 content blocks，执行工具调用 ----
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();

                    // 从 JsonValue 提取输入参数
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>)
                            jsonValueToObject(toolUse._input());
                    if (input == null) input = Map.of();

                    // 执行工具分发
                    String toolName = toolUse.name();
                    String output = dispatchLeadTool(toolName, input);

                    // 打印正在执行的工具（黄色高亮）
                    System.out.println(ANSI_YELLOW + "> " + toolName
                            + ": " + truncate(output, PREVIEW_LEN) + ANSI_RESET);

                    // 截断过长的输出
                    if (output.length() > MAX_OUTPUT) {
                        output = output.substring(0, MAX_OUTPUT);
                    }

                    // 构造 tool_result block
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                }
            }

            // ---- 5. 将工具结果追加为 user 消息 ----
            if (!toolResults.isEmpty()) {
                paramsBuilder.addUserMessageOfBlockParams(toolResults);
            }
        }
    }

    // ==================== Lead 工具分发 ====================

    /**
     * Lead 工具分发器 —— 根据 toolName 路由到对应的处理逻辑。
     * <p>
     * S01 只有一个 bash 工具，S15 有 9 个工具需要分发。
     * 使用 if-else 链而非 Map 分发器，保持代码的直白性和教学清晰度。
     *
     * @param toolName 工具名称
     * @param input    工具输入参数
     * @return 工具执行结果
     */
    private static String dispatchLeadTool(String toolName, Map<String, Object> input) {
        return switch (toolName) {
            // ---- 基础工具（4 个） ----
            case "bash" -> runBash((String) input.get("command"));
            case "read_file" -> runRead(
                    (String) input.get("path"),
                    input.get("limit") instanceof Number n ? n.intValue() : null);
            case "write_file" -> runWrite(
                    (String) input.get("path"),
                    (String) input.get("content"));
            case "edit_file" -> runEdit(
                    (String) input.get("path"),
                    (String) input.get("old_text"),
                    (String) input.get("new_text"));
            // ---- 团队工具（5 个） ----
            case "spawn_teammate" -> teamMgr.spawn(
                    (String) input.get("name"),
                    (String) input.get("role"),
                    (String) input.get("prompt"));
            case "list_teammates" -> teamMgr.listAll();
            case "send_message" -> bus.send(
                    "lead",
                    (String) input.get("to"),
                    (String) input.get("content"),
                    (String) input.getOrDefault("msg_type", "message"),
                    null);
            case "read_inbox" -> {
                // 读取并排空 lead 的收件箱
                try {
                    List<Map<String, Object>> msgs = bus.readInbox("lead");
                    yield MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(msgs);
                } catch (Exception e) {
                    yield "[]";
                }
            }
            case "broadcast" -> bus.broadcast(
                    "lead",
                    (String) input.get("content"),
                    teamMgr.memberNames());
            default -> "Error: Unknown tool '" + toolName + "'";
        };
    }

    // ==================== 内部类：消息总线 ====================

    /**
     * JSONL 邮箱消息总线 —— 基于文件的 Agent 间通信（自包含实现）。
     * <p>
     * 每个 teammate 拥有独立的 JSONL 文件作为收件箱：
     * <pre>
     * .team/inbox/
     *   alice.jsonl    ← alice 的收件箱
     *   bob.jsonl      ← bob 的收件箱
     *   lead.jsonl     ← 主 Agent 的收件箱
     * </pre>
     * <p>
     * 并发安全：每个 inbox 文件一把独立的 {@link ReentrantLock}，
     * 使用 {@link ConcurrentHashMap} 管理锁池。
     * <p>
     * 写入方式：append-only，一行一条 JSON 消息。
     * 读取方式：read-and-drain（读取后清空文件）。
     * <p>
     * 对应 Python 原版：s09/s15_agent_teams.py 中的 MessageBus 类。
     */
    static class MessageBus {

        /** 收件箱目录（.team/inbox/） */
        private final Path inboxDir;

        /** 每个收件箱文件的独立锁（防止并发写入数据竞争） */
        private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

        /**
         * 构造消息总线。
         *
         * @param inboxDir 收件箱目录路径（如 .team/inbox/）
         */
        MessageBus(Path inboxDir) {
            this.inboxDir = inboxDir;
            try {
                Files.createDirectories(inboxDir);
            } catch (IOException e) {
                System.err.println(printRed("创建 inbox 目录失败: " + e.getMessage()));
            }
        }

        /**
         * 发送消息到指定 teammate 的收件箱。
         * <p>
         * 消息格式：一行 JSON，包含 type、from、content、timestamp 等字段。
         *
         * @param sender  发送者名称
         * @param to      接收者名称
         * @param content 消息内容
         * @param msgType 消息类型（message/broadcast/shutdown_request 等）
         * @param extra   附加字段（可为 null）
         * @return 操作确认字符串
         */
        String send(String sender, String to, String content,
                    String msgType, Map<String, Object> extra) {
            // 校验消息类型
            if (!VALID_MSG_TYPES.contains(msgType)) {
                return "Error: Invalid type '" + msgType + "'. Valid: " + VALID_MSG_TYPES;
            }

            // 构建消息体
            var msg = new LinkedHashMap<String, Object>();
            msg.put("type", msgType);
            msg.put("from", sender);
            msg.put("content", content);
            msg.put("timestamp", System.currentTimeMillis() / 1000.0);
            if (extra != null) {
                msg.putAll(extra);
            }

            // 获取该收件箱的锁，保证并发安全
            ReentrantLock lock = locks.computeIfAbsent(to, k -> new ReentrantLock());
            lock.lock();
            try {
                Path inboxPath = inboxDir.resolve(to + ".jsonl");
                String line = MAPPER.writeValueAsString(msg) + "\n";
                Files.writeString(inboxPath, line,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return "Sent " + msgType + " to " + to;
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            } finally {
                lock.unlock();
            }
        }

        /**
         * 读取并清空收件箱（drain 语义）。
         * <p>
         * 读取 inbox 目录下的 {name}.jsonl 文件，解析每行 JSON，
         * 然后清空文件。这是一个原子操作（通过锁保护）。
         *
         * @param name teammate 名称
         * @return 消息列表（读取后收件箱被清空）
         */
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> readInbox(String name) {
            Path inboxPath = inboxDir.resolve(name + ".jsonl");
            if (!Files.exists(inboxPath)) {
                return List.of();
            }

            ReentrantLock lock = locks.computeIfAbsent(name, k -> new ReentrantLock());
            lock.lock();
            try {
                String text = Files.readString(inboxPath).trim();
                if (text.isEmpty()) {
                    return List.of();
                }

                // 逐行解析 JSON 消息
                var messages = new ArrayList<Map<String, Object>>();
                for (String line : text.split("\n")) {
                    if (!line.isBlank()) {
                        messages.add(MAPPER.readValue(line, Map.class));
                    }
                }

                // 清空收件箱文件（drain 语义）
                Files.writeString(inboxPath, "");
                return messages;
            } catch (IOException e) {
                System.err.println(printRed("读取收件箱失败: " + e.getMessage()));
                return List.of();
            } finally {
                lock.unlock();
            }
        }

        /**
         * 广播消息给所有 teammate（排除发送者自身）。
         *
         * @param sender    发送者名称
         * @param content   消息内容
         * @param teammates 所有益友名称列表
         * @return 广播确认信息
         */
        String broadcast(String sender, String content, List<String> teammates) {
            int count = 0;
            for (String name : teammates) {
                if (!name.equals(sender)) {
                    send(sender, name, content, "broadcast", null);
                    count++;
                }
            }
            return "Broadcast to " + count + " teammates";
        }
    }

    // ==================== 内部类：队友管理器 ====================

    /**
     * 队友管理器 —— 管理持久化 Teammate 的生命周期（自包含实现）。
     * <p>
     * 职责：
     * <ul>
     *   <li>管理团队配置（.team/config.json）</li>
     *   <li>生成/更新 teammate（spawn）</li>
     *   <li>运行 teammate 工作循环（teammateLoop）</li>
     *   <li>查询团队成员状态（listAll/memberNames）</li>
     * </ul>
     * <p>
     * 团队配置文件格式（.team/config.json）：
     * <pre>
     * {
     *   "team_name": "default",
     *   "members": [
     *     {"name": "alice", "role": "coder", "status": "working"},
     *     {"name": "bob", "role": "reviewer", "status": "idle"}
     *   ]
     * }
     * </pre>
     * <p>
     * 对应 Python 原版：s09/s15_agent_teams.py 中的 TeammateManager 类。
     */
    static class TeammateManager {

        /** 团队配置目录（.team/） */
        private final Path teamDir;

        /** 团队配置文件路径（.team/config.json） */
        private final Path configPath;

        /** 消息总线引用 */
        private final MessageBus bus;

        /** 团队配置（内存中的 Map） */
        private Map<String, Object> config;

        /**
         * 构造队友管理器。
         *
         * @param teamDir 团队配置目录（.team/）
         * @param bus     消息总线实例
         */
        TeammateManager(Path teamDir, MessageBus bus) {
            this.teamDir = teamDir;
            this.configPath = teamDir.resolve("config.json");
            this.bus = bus;
            this.config = loadConfig();
        }

        // ==================== 配置管理 ====================

        /**
         * 加载团队配置文件。
         * <p>
         * 如果配置文件不存在，创建默认配置。
         *
         * @return 团队配置 Map
         */
        @SuppressWarnings("unchecked")
        private Map<String, Object> loadConfig() {
            try {
                Files.createDirectories(teamDir);
            } catch (IOException ignored) {}
            if (Files.exists(configPath)) {
                try {
                    return MAPPER.readValue(Files.readString(configPath), Map.class);
                } catch (IOException ignored) {}
            }
            // 创建默认配置
            var cfg = new LinkedHashMap<String, Object>();
            cfg.put("team_name", "default");
            cfg.put("members", new ArrayList<Map<String, Object>>());
            return cfg;
        }

        /**
         * 保存团队配置到文件。
         */
        private synchronized void saveConfig() {
            try {
                Files.writeString(configPath,
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(config));
            } catch (IOException ignored) {}
        }

        /**
         * 查找指定名称的团队成员。
         *
         * @param name 成员名称
         * @return 成员信息 Map，不存在则返回 null
         */
        @SuppressWarnings("unchecked")
        private synchronized Map<String, Object> findMember(String name) {
            var members = (List<Map<String, Object>>) config.get("members");
            return members.stream()
                    .filter(m -> name.equals(m.get("name")))
                    .findFirst().orElse(null);
        }

        /**
         * 更新团队成员状态。
         *
         * @param name   成员名称
         * @param status 新状态（working/idle/shutdown）
         */
        @SuppressWarnings("unchecked")
        private synchronized void setMemberStatus(String name, String status) {
            var member = findMember(name);
            if (member != null) {
                member.put("status", status);
                saveConfig();
            }
        }

        // ==================== 队友操作 ====================

        /**
         * 生成（或重新激活）一个持久化队友。
         * <p>
         * 工作流程：
         * <ol>
         *   <li>检查是否已存在同名队友</li>
         *   <li>如果存在且状态为 idle/shutdown，重新激活</li>
         *   <li>如果不存在，创建新成员记录</li>
         *   <li>更新配置文件</li>
         *   <li>启动虚拟线程运行 teammateLoop</li>
         * </ol>
         *
         * @param name   队友名称（唯一标识）
         * @param role   队友角色（如 coder、reviewer、researcher）
         * @param prompt 初始工作指令
         * @return 操作确认信息
         */
        @SuppressWarnings("unchecked")
        synchronized String spawn(String name, String role, String prompt) {
            // 查找已有成员
            var member = findMember(name);
            if (member != null) {
                String status = (String) member.get("status");
                // 只有 idle 或 shutdown 状态的队友可以被重新激活
                if (!"idle".equals(status) && !"shutdown".equals(status)) {
                    return "Error: '" + name + "' is currently " + status;
                }
                // 重新激活：更新角色和状态
                member.put("status", "working");
                member.put("role", role);
            } else {
                // 创建新成员
                member = new LinkedHashMap<>(Map.of(
                        "name", name, "role", role, "status", "working"));
                ((List<Map<String, Object>>) config.get("members")).add(member);
            }
            saveConfig();

            // 启动虚拟线程运行队友工作循环
            // Thread.ofVirtual() 是 Java 21 的虚拟线程特性
            // 虚拟线程非常轻量，可以创建数千个而不会耗尽系统资源
            Thread.ofVirtual().name("agent-" + name)
                    .start(() -> teammateLoop(name, role, prompt));

            return "Spawned '" + name + "' (role: " + role + ")";
        }

        /**
         * 列出所有团队成员及其状态。
         * <p>
         * 格式示例：
         * <pre>
         * Team: default
         *   alice (coder): working
         *   bob (reviewer): idle
         * </pre>
         *
         * @return 格式化的团队状态字符串
         */
        @SuppressWarnings("unchecked")
        String listAll() {
            var members = (List<Map<String, Object>>) config.get("members");
            if (members.isEmpty()) return "No teammates.";
            var lines = new ArrayList<String>();
            lines.add("Team: " + config.get("team_name"));
            for (var m : members) {
                lines.add("  " + m.get("name") + " (" + m.get("role")
                        + "): " + m.get("status"));
            }
            return String.join("\n", lines);
        }

        /**
         * 获取所有成员名称列表。
         *
         * @return 成员名称列表
         */
        @SuppressWarnings("unchecked")
        List<String> memberNames() {
            var members = (List<Map<String, Object>>) config.get("members");
            return members.stream()
                    .map(m -> (String) m.get("name"))
                    .toList();
        }

        // ==================== Teammate 工作循环 ====================

        /**
         * Teammate 工作循环 —— 简化版（无 idle 轮询，无任务认领）。
         * <p>
         * 这是 S15 的简化版 teammate 循环。与 S11/SFull 的完整版不同：
         * <ul>
         *   <li>S15：work phase → idle 即退出，无轮询无自动认领</li>
         *   <li>S17：work phase → idle polling → 自动认领任务 → 循环</li>
         * </ul>
         * <p>
         * 工作流程：
         * <pre>
         * WORK PHASE:
         *   for round in range(MAX_WORK_ROUNDS):
         *     1. 读取收件箱
         *     2. 调用 LLM
         *     3. 执行工具
         *     4. 如果 LLM 停止（非 TOOL_USE）→ 退出工作阶段
         * 完成后设置状态为 idle
         * </pre>
         * <p>
         * 每个 teammate 有独立的 MessageCreateParams.Builder，
         * 直接调用 client.messages().create()，不依赖 Lead 的循环。
         *
         * @param name   队友名称
         * @param role   队友角色
         * @param prompt 初始工作指令
         */
        private void teammateLoop(String name, String role, String prompt) {
            // ---- 构建系统提示词 ----
            // 告诉 teammate 它是谁、什么角色、在哪个团队
            String teamName = (String) config.getOrDefault("team_name", "default");
            String sysPrompt = "You are '" + name + "', role: " + role
                    + ", team: " + teamName + ", at " + WORKDIR + ". "
                    + "Do your assigned work, then stop.";

            // ---- 构建 teammate 参数 ----
            // 每个 teammate 有自己独立的 paramsBuilder（对话历史）
            var params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(MAX_TOKENS)
                    .system(sysPrompt);

            // ---- Teammate 工具集（6 个） ----
            // bash, read_file, write_file, edit_file, send_message, read_inbox
            List<Tool> tools = List.of(
                    defineTool("bash", "Run a shell command.",
                            Map.of("command", Map.of("type", "string")),
                            List.of("command")),
                    defineTool("read_file", "Read file contents.",
                            Map.of("path", Map.of("type", "string")),
                            List.of("path")),
                    defineTool("write_file", "Write content to file.",
                            Map.of("path", Map.of("type", "string"),
                                    "content", Map.of("type", "string")),
                            List.of("path", "content")),
                    defineTool("edit_file", "Replace exact text in file.",
                            Map.of("path", Map.of("type", "string"),
                                    "old_text", Map.of("type", "string"),
                                    "new_text", Map.of("type", "string")),
                            List.of("path", "old_text", "new_text")),
                    defineTool("send_message", "Send message to a teammate.",
                            Map.of("to", Map.of("type", "string"),
                                    "content", Map.of("type", "string")),
                            List.of("to", "content")),
                    defineTool("read_inbox", "Read and drain your inbox.",
                            Map.of(), null)
            );
            for (Tool tool : tools) {
                params.addTool(tool);
            }

            // 注入初始工作指令
            params.addUserMessage(prompt);

            // ---- WORK PHASE: 标准工作循环，最多 MAX_WORK_ROUNDS 轮 ----
            for (int round = 0; round < MAX_WORK_ROUNDS; round++) {
                // 每轮开始前读取收件箱
                List<Map<String, Object>> inbox = bus.readInbox(name);
                for (var msg : inbox) {
                    try {
                        // 将收件箱消息注入到对话历史
                        params.addUserMessage(MAPPER.writeValueAsString(msg));
                    } catch (IOException ignored) {}
                }

                try {
                    // ---- 调用 LLM ----
                    // teammate 直接调用 client，不经过 Lead
                    Message resp = client.messages().create(params.build());
                    params.addMessage(resp);

                    // ---- 检查停止原因 ----
                    boolean isToolUse = resp.stopReason()
                            .map(StopReason.TOOL_USE::equals)
                            .orElse(false);

                    if (!isToolUse) {
                        // LLM 决定停止（END_TURN）→ 退出工作阶段
                        // 打印 teammate 的最终文本回复
                        for (ContentBlock block : resp.content()) {
                            block.text().ifPresent(tb ->
                                    System.out.println(printDim(
                                            "  [" + name + "] " + tb.text())));
                        }
                        break;
                    }

                    // ---- 执行工具调用 ----
                    List<ContentBlockParam> results = new ArrayList<>();

                    for (ContentBlock block : resp.content()) {
                        if (block.isToolUse()) {
                            ToolUseBlock tu = block.asToolUse();
                            String toolName = tu.name();

                            // ===== 常规工具分发 =====
                            @SuppressWarnings("unchecked")
                            Map<String, Object> input = (Map<String, Object>)
                                    jsonValueToObject(tu._input());
                            if (input == null) input = Map.of();

                            String output = dispatchTeammateTool(name, toolName, input);

                            // 用灰色打印 teammate 的工具调用（区分于 Lead 的黄色）
                            System.out.println(printDim(
                                    "  [" + name + "] " + toolName + ": "
                                            + truncate(output, 120)));

                            if (output.length() > MAX_OUTPUT) {
                                output = output.substring(0, MAX_OUTPUT);
                            }

                            results.add(ContentBlockParam.ofToolResult(
                                    ToolResultBlockParam.builder()
                                            .toolUseId(tu.id())
                                            .content(output)
                                            .build()));
                        }
                    }

                    // 将工具结果追加为 user 消息
                    params.addUserMessageOfBlockParams(results);

                } catch (Exception e) {
                    // 发生异常时打印错误并退出
                    System.out.println(printRed(
                            "  [" + name + "] Error: " + e.getMessage()));
                    setMemberStatus(name, "shutdown");
                    return;
                }
            }

            // ---- 工作阶段结束，设置状态为 idle ----
            // S15 简化版：工作自然完成后设为 idle，可以被 respawn 重新激活
            // 只有异常（见 catch 块）才设为 shutdown
            setMemberStatus(name, "idle");
            System.out.println(printDim("  [" + name + "] idle."));
        }

        /**
         * Teammate 工具分发器。
         * <p>
         * 与 Lead 的工具集相比，teammate 只有 6 个常规工具。
         *
         * @param name     teammate 名称（用于 send_message 的 sender 字段）
         * @param toolName 工具名称
         * @param input    工具输入参数
         * @return 工具执行结果
         */
        private String dispatchTeammateTool(String name, String toolName,
                                            Map<String, Object> input) {
            return switch (toolName) {
                case "bash" -> runBash((String) input.get("command"));
                case "read_file" -> runRead(
                        (String) input.get("path"),
                        input.get("limit") instanceof Number n ? n.intValue() : null);
                case "write_file" -> runWrite(
                        (String) input.get("path"),
                        (String) input.get("content"));
                case "edit_file" -> runEdit(
                        (String) input.get("path"),
                        (String) input.get("old_text"),
                        (String) input.get("new_text"));
                case "send_message" -> bus.send(
                        name,
                        (String) input.get("to"),
                        (String) input.get("content"),
                        "message", null);
                case "read_inbox" -> {
                    try {
                        List<Map<String, Object>> msgs = bus.readInbox(name);
                        yield MAPPER.writerWithDefaultPrettyPrinter()
                                .writeValueAsString(msgs);
                    } catch (Exception e) {
                        yield "[]";
                    }
                }
                default -> "Error: Unknown tool '" + toolName + "'";
            };
        }
    }

    // ==================== 基础设施：客户端构建 ====================

    /**
     * 构建 Anthropic API 客户端。
     * <p>
     * 支持自定义 baseUrl（用于第三方 API 兼容端点，如 OpenRouter、Azure 等）。
     *
     * @param apiKey  Anthropic API 密钥
     * @param baseUrl 可选的自定义 API 端点 URL
     * @return 配置好的 AnthropicClient 实例
     */
    private static AnthropicClient buildClient(String apiKey, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();
        }
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    // ==================== 基础设施：工具定义 ====================

    /**
     * 便捷方法：构建一个 Tool 定义，用于发送给 LLM。
     * <p>
     * 工具定义描述了工具的名称、用途和输入参数格式（JSON Schema）。
     * LLM 根据这些信息决定何时调用哪个工具、传什么参数。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param properties  输入参数的 JSON Schema properties
     * @param required    必填参数名称列表
     * @return 构建好的 Tool 实例
     */
    private static Tool defineTool(String name, String description,
                                   Map<String, Object> properties,
                                   List<String> required) {
        var schemaBuilder = Tool.InputSchema.builder()
                .properties(JsonValue.from(properties));
        if (required != null && !required.isEmpty()) {
            schemaBuilder.putAdditionalProperty("required", JsonValue.from(required));
        }
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schemaBuilder.build())
                .build();
    }

    // ==================== 基础设施：Bash 执行 ====================

    /**
     * 执行 bash / cmd 命令。
     * <p>
     * 安全特性：
     * <ul>
     *   <li>危险命令黑名单检查</li>
     *   <li>120 秒超时限制</li>
     *   <li>输出截断到 50000 字符</li>
     *   <li>OS 自适应：Unix 用 bash -c，Windows 用 cmd /c</li>
     * </ul>
     *
     * @param command 要执行的 shell 命令
     * @return 命令输出，或错误信息
     */
    private static String runBash(String command) {
        if (command == null || command.isBlank()) {
            return "Error: command is required";
        }

        // 危险命令检查
        for (String dangerous : DANGEROUS_COMMANDS) {
            if (command.contains(dangerous)) {
                return "Error: Dangerous command blocked";
            }
        }

        try {
            // OS 自适应：Windows 用 cmd /c，其他用 bash -c
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            pb.directory(WORKDIR.toFile());
            pb.redirectErrorStream(true); // 合并 stdout 和 stderr

            Process process = pb.start();

            // 读取进程输出
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append("\n");
                    }
                    output.append(line);
                    // 提前截断
                    if (output.length() > MAX_OUTPUT) {
                        break;
                    }
                }
            }

            // 等待进程结束（带超时）
            boolean finished = process.waitFor(
                    BASH_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (" + BASH_TIMEOUT_SECONDS + "s)";
            }

            String result = output.toString().trim();
            if (result.isEmpty()) {
                return "(no output)";
            }
            return result.length() > MAX_OUTPUT
                    ? result.substring(0, MAX_OUTPUT)
                    : result;

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 基础设施：文件读取 ====================

    /**
     * 读取文件内容。
     * <p>
     * 支持可选的行数限制。使用 safePath() 防止路径遍历攻击。
     *
     * @param path  文件路径（相对于 WORKDIR 或绝对路径）
     * @param limit 可选的读取行数限制
     * @return 文件内容，或错误信息
     */
    private static String runRead(String path, Integer limit) {
        if (path == null || path.isBlank()) {
            return "Error: path is required";
        }
        Path resolved = safePath(path);
        if (resolved == null) {
            return "Error: Path traversal blocked";
        }
        if (!Files.exists(resolved)) {
            return "Error: File not found: " + path;
        }
        try {
            List<String> lines = Files.readAllLines(resolved);
            // 应用行数限制
            if (limit != null && limit > 0 && lines.size() > limit) {
                lines = lines.subList(0, limit);
            }
            String content = String.join("\n", lines);
            // 截断过长内容
            return content.length() > MAX_OUTPUT
                    ? content.substring(0, MAX_OUTPUT)
                    : content;
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 基础设施：文件写入 ====================

    /**
     * 写入文件内容。
     * <p>
     * 如果父目录不存在会自动创建。使用 safePath() 防止路径遍历。
     *
     * @param path    文件路径
     * @param content 要写入的内容
     * @return 操作确认信息
     */
    private static String runWrite(String path, String content) {
        if (path == null || path.isBlank()) {
            return "Error: path is required";
        }
        Path resolved = safePath(path);
        if (resolved == null) {
            return "Error: Path traversal blocked";
        }
        try {
            // 自动创建父目录
            Files.createDirectories(resolved.getParent());
            Files.writeString(resolved, content != null ? content : "");
            return "Wrote " + resolved.getFileName();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 基础设施：文件编辑 ====================

    /**
     * 替换文件中的精确文本。
     * <p>
     * 使用 String.replaceFirst 进行首次出现的精确替换。
     * old_text 必须在文件中精确匹配（包括空白和缩进）。
     *
     * @param path     文件路径
     * @param oldText  要被替换的原始文本
     * @param newText  替换后的新文本
     * @return 操作确认信息
     */
    private static String runEdit(String path, String oldText, String newText) {
        if (path == null || path.isBlank()) {
            return "Error: path is required";
        }
        if (oldText == null || oldText.isEmpty()) {
            return "Error: old_text is required";
        }
        Path resolved = safePath(path);
        if (resolved == null) {
            return "Error: Path traversal blocked";
        }
        if (!Files.exists(resolved)) {
            return "Error: File not found: " + path;
        }
        try {
            String content = Files.readString(resolved);
            // 精确匹配第一次出现的 old_text
            int idx = content.indexOf(oldText);
            if (idx < 0) {
                return "Error: old_text not found in " + path;
            }
            String newContent = content.substring(0, idx)
                    + (newText != null ? newText : "")
                    + content.substring(idx + oldText.length());
            Files.writeString(resolved, newContent);
            return "Edited " + resolved.getFileName();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 基础设施：路径安全 ====================

    /**
     * 安全路径解析 —— 防止路径遍历攻击。
     * <p>
     * 将用户提供的路径解析为绝对路径，并检查它是否在 WORKDIR 内。
     * 如果路径试图逃逸沙箱（如 ../../etc/passwd），返回 null。
     *
     * @param path 用户提供的路径字符串
     * @return 解析后的安全绝对路径，或 null（如果路径不安全）
     */
    private static Path safePath(String path) {
        if (path == null || path.isBlank()) return null;
        Path resolved = WORKDIR.resolve(path).normalize().toAbsolutePath();
        // 检查解析后的路径是否仍在工作目录内
        if (!resolved.startsWith(WORKDIR)) {
            return null; // 路径遍历攻击被阻止
        }
        return resolved;
    }

    // ==================== 基础设施：JsonValue 转换 ====================

    /**
     * 将 SDK 的 JsonValue 递归转换为普通 Java 对象。
     * <p>
     * Anthropic SDK 使用 JsonValue 类型表示 JSON 数据，但工具执行时
     * 需要普通 Java 类型（Map、List、String 等）来提取参数。
     * <p>
     * 转换规则：
     * <ul>
     *   <li>JsonValue(String) → Java String</li>
     *   <li>JsonValue(Number) → Java Number</li>
     *   <li>JsonValue(Boolean) → Java Boolean</li>
     *   <li>JsonValue(Object) → LinkedHashMap&lt;String, Object&gt;</li>
     *   <li>JsonValue(Array) → ArrayList&lt;Object&gt;</li>
     * </ul>
     *
     * @param value SDK 的 JsonValue 实例
     * @return 转换后的普通 Java 对象
     */
    @SuppressWarnings("unchecked")
    private static Object jsonValueToObject(JsonValue value) {
        if (value == null) return null;

        // String（最常见）
        var strOpt = value.asString();
        if (strOpt.isPresent()) return strOpt.get();

        // Number
        var numOpt = value.asNumber();
        if (numOpt.isPresent()) return numOpt.get();

        // Boolean
        var boolOpt = value.asBoolean();
        if (boolOpt.isPresent()) return boolOpt.get();

        // Object（JSON 对象 → LinkedHashMap）
        try {
            var mapOpt = value.asObject();
            if (mapOpt.isPresent()) {
                Map<String, JsonValue> raw = (Map<String, JsonValue>) (Object) mapOpt.get();
                Map<String, Object> result = new LinkedHashMap<>();
                for (var entry : raw.entrySet()) {
                    result.put(entry.getKey(), jsonValueToObject(entry.getValue()));
                }
                return result;
            }
        } catch (ClassCastException ignored) {}

        // Array（JSON 数组 → ArrayList）
        try {
            var listOpt = value.asArray();
            if (listOpt.isPresent()) {
                List<JsonValue> raw = (List<JsonValue>) (Object) listOpt.get();
                List<Object> result = new ArrayList<>();
                for (JsonValue item : raw) {
                    result.add(jsonValueToObject(item));
                }
                return result;
            }
        } catch (ClassCastException ignored) {}

        return null;
    }

    // ==================== 基础设施：ANSI 颜色输出 ====================

    /** 将文本包装为 ANSI 青色（REPL 提示符） */
    private static String printCyan(String text) {
        return ANSI_CYAN + text + ANSI_RESET;
    }

    /** 将文本包装为 ANSI 灰色/暗色（teammate 工具输出） */
    private static String printDim(String text) {
        return ANSI_DIM + text + ANSI_RESET;
    }

    /** 将文本包装为 ANSI 红色（错误信息） */
    private static String printRed(String text) {
        return ANSI_RED + text + ANSI_RESET;
    }

    /** 截断字符串到指定长度 */
    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
