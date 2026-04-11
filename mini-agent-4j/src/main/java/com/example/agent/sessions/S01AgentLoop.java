package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;

/**
 * S01：Agent 循环 —— 模型与真实世界的第一次连接（自包含实现）。
 * <p>
 * 这是整个课程体系中最重要的一课。AI 编码 Agent 的全部秘密浓缩在
 * 一个核心模式中：
 * <pre>
 *   while (stopReason == TOOL_USE) {
 *       response = LLM(messages, tools);
 *       execute tools;
 *       append results;
 *   }
 * </pre>
 * <p>
 * 整个文件完全自包含——不依赖 com.example.agent.* 下的任何类。
 * 所有基础设施（客户端构建、环境变量加载、工具定义、bash 执行、
 * JsonValue 转换、ANSI 颜色输出）全部内联实现。
 * <p>
 * 外部依赖仅有：
 * <ul>
 *   <li>com.anthropic.* — Anthropic Java SDK</li>
 *   <li>io.github.cdimascio.dotenv.* — dotenv-java 环境变量加载</li>
 *   <li>java standard library — JDK 标准库</li>
 * </ul>
 * <p>
 * 对应 Python 原版：s01_agent_loop.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s01_agent_loop.py">Python 原版</a>
 */
public class S01AgentLoop {

    // ==================== 常量定义 ====================

    /** 工作目录 —— Agent 的文件系统沙箱根目录 */
    private static final Path WORKDIR = Path.of(System.getProperty("user.dir")).toAbsolutePath();

    /** 最大输出 token 数（与 Python 原版一致） */
    private static final long MAX_TOKENS = 8000;

    /** Bash 命令执行超时时间（秒） */
    private static final int BASH_TIMEOUT_SECONDS = 120;

    /** Bash 输出截断上限（字符数），与 Python 原版的 50000 一致 */
    private static final int MAX_OUTPUT = 50000;

    /** 工具结果预览打印长度 */
    private static final int PREVIEW_LEN = 200;

    /** 危险命令黑名单（防止 Agent 搞破坏） */
    private static final List<String> DANGEROUS_COMMANDS = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    /** ANSI 重置码 */
    private static final String ANSI_RESET = "\033[0m";
    /** ANSI 黄色（用于打印执行的命令） */
    private static final String ANSI_YELLOW = "\033[33m";
    /** ANSI 青色（用于 REPL 提示符） */
    private static final String ANSI_CYAN = "\033[36m";

    // ==================== 主入口 ====================

    /**
     * 自包含的 S01 Agent 主方法。
     * <p>
     * 整个运行流程：
     * <ol>
     *   <li>加载 .env 环境变量（API Key、模型 ID 等）</li>
     *   <li>构建 Anthropic API 客户端</li>
     *   <li>定义唯一的 bash 工具</li>
     *   <li>进入 REPL 主循环：读取用户输入 → 执行 Agent 循环 → 输出结果</li>
     * </ol>
     */
    public static void main(String[] args) {
        // ---- 1. 加载环境变量 ----
        // 从项目根目录的 .env 文件加载配置（API Key、模型 ID、可选的 Base URL）
        // ignoreIfMissing() 确保没有 .env 文件时也不报错，回退到系统环境变量
        Dotenv dotenv = Dotenv.configure()
                .directory(WORKDIR.toString())
                .ignoreIfMissing()
                .load();

        // 如果设置了自定义 Base URL（第三方兼容端点），清除 ANTHROPIC_AUTH_TOKEN
        // 避免认证冲突。这与 Python 原版的行为一致。
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

        String model = dotenv.get("MODEL_ID");
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "MODEL_ID 未配置。请在 .env 文件或系统环境变量中设置。");
        }

        // ---- 3. 构建 Anthropic 客户端 ----
        AnthropicClient client = buildClient(apiKey, baseUrl);

        // ---- 4. 系统提示词 ----
        // 最简系统提示：告诉模型它是谁、在哪、该做什么
        // "Act first, then report clearly" 引导模型先执行后汇报
        String systemPrompt = "You are a coding agent at " + WORKDIR
                + ". Use bash to inspect and change the workspace. "
                + "Act first, then report clearly.";

        // ---- 5. 工具定义：仅 bash ----
        // S01 是最小可运行的 Agent，只配备一个 bash 工具
        // 后续章节会在此基础上添加 read_file、write_file、edit_file 等
        Tool bashTool = defineTool("bash",
                "Run a shell command in the current workspace.",
                Map.of("command", Map.of("type", "string")),
                List.of("command"));

        // ---- 6. 构建初始参数 ----
        // MessageCreateParams.Builder 贯穿整个 REPL 生命周期，
        // 所有对话历史都累积在这个 builder 中
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(systemPrompt)
                .addTool(bashTool);

        // ---- 7. REPL 主循环 ----
        // 与 Python 原版完全一致：读取输入 → 调用 Agent 循环 → 打印最终回复
        Scanner scanner = new Scanner(System.in);

        while (true) {
            // 打印青色提示符（与 Python 版的 \033[36ms01 >> \033[0m 一致）
            System.out.print(printCyan("s01 >> "));

            // 处理 Ctrl+D / Ctrl+C（EOF）
            if (!scanner.hasNextLine()) {
                break;
            }

            String query = scanner.nextLine().trim();

            // 退出命令：q、exit、空行
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // 追加用户消息到对话历史
            paramsBuilder.addUserMessage(query);

            // 执行 Agent 循环：LLM → 工具执行 → 结果回传，直到模型停止
            try {
                agentLoop(client, paramsBuilder);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }

            // 每轮结束后空一行，改善可读性
            System.out.println();
        }

        System.out.println("Bye!");
    }

    // ==================== 核心循环 ====================

    /**
     * Agent 核心循环 —— 整个 Agent 系统的心脏。
     * <p>
     * 循环逻辑极其简洁：
     * <pre>
     * while (stopReason == TOOL_USE) {
     *     1. 调用 LLM 获取回复
     *     2. 将 assistant 回复追加到对话历史
     *     3. 如果模型请求停止（非 TOOL_USE），打印文本并返回
     *     4. 否则，遍历 content blocks 找到所有 tool_use 调用
     *     5. 执行每个工具，收集结果
     *     6. 将工具结果追加为 user 消息
     *     7. 继续下一轮循环
     * }
     * </pre>
     * <p>
     * 产品级 Agent（s02-s12）都是在这个循环上增加"装置"：
     * 上下文压缩、后台任务、团队通信等。循环本身从不改变。
     *
     * @param client        Anthropic API 客户端
     * @param paramsBuilder 消息参数构建器（对话历史在此累积）
     */
    private static void agentLoop(AnthropicClient client,
                                  MessageCreateParams.Builder paramsBuilder) {
        while (true) {
            // ---- 1. 调用 LLM ----
            // 发送完整对话历史给模型，获取回复
            Message response = client.messages().create(paramsBuilder.build());

            // ---- 2. 将 assistant 回复追加到历史 ----
            // paramsBuilder.addMessage(response) 会自动展开 response.content()
            // 中的所有 block 为对应的 ContentBlockParam
            paramsBuilder.addMessage(response);

            // ---- 3. 检查停止原因 ----
            // stopReason 有两种主要情况：
            //   - TOOL_USE：模型想要调用工具，继续循环
            //   - END_TURN：模型认为对话可以结束，打印文本后返回
            boolean isToolUse = response.stopReason()
                    .map(StopReason.TOOL_USE::equals)
                    .orElse(false);

            if (!isToolUse) {
                // 模型决定停止对话，提取并打印文本回复
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(textBlock ->
                            System.out.println(textBlock.text()));
                }
                return; // 退出循环，回到 REPL 等待下一个用户输入
            }

            // ---- 4. 遍历 content blocks，执行工具调用 ----
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                // 只处理 tool_use 类型的 block，跳过文本 block
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();

                    // 从 JsonValue 提取输入参数为普通 Java Map
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>)
                            jsonValueToObject(toolUse._input());

                    // 获取要执行的命令
                    String command = input != null
                            ? (String) input.get("command")
                            : "";

                    // 打印正在执行的命令（黄色高亮，与 Python 版一致）
                    System.out.println(ANSI_YELLOW + "$ " + command + ANSI_RESET);

                    // 执行 bash 命令
                    String output = runBash(command);

                    // 打印结果预览（前 200 字符）
                    System.out.println(output.length() > PREVIEW_LEN
                            ? output.substring(0, PREVIEW_LEN)
                            : output);

                    // 构造 tool_result block，关联 tool_use_id
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                }
            }

            // ---- 5. 将工具结果追加为 user 消息 ----
            // Anthropic API 要求工具结果必须以 role=user 的消息发送
            if (!toolResults.isEmpty()) {
                paramsBuilder.addUserMessageOfBlockParams(toolResults);
            }
        }
    }

    // ==================== 基础设施：客户端构建 ====================

    /**
     * 构建 Anthropic API 客户端。
     * <p>
     * 支持自定义 baseUrl（用于第三方 API 兼容端点，如 OpenRouter、Azure 等）。
     * 如果 baseUrl 为空或 null，则使用 Anthropic 官方端点。
     *
     * @param apiKey  Anthropic API 密钥
     * @param baseUrl 可选的自定义 API 端点 URL
     * @return 配置好的 AnthropicClient 实例
     */
    private static AnthropicClient buildClient(String apiKey, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            // 使用自定义端点（第三方 API 兼容）
            return AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(baseUrl)
                    .build();
        }
        // 使用 Anthropic 官方端点
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
     * <p>
     * 示例：
     * <pre>
     * Tool bashTool = defineTool("bash", "Run a shell command.",
     *     Map.of("command", Map.of("type", "string")),
     *     List.of("command"));
     * </pre>
     *
     * @param name        工具名称（LLM 调用时使用）
     * @param description 工具描述（帮助 LLM 理解何时使用此工具）
     * @param properties  输入参数的 JSON Schema properties
     * @param required    必填参数名称列表
     * @return 构建好的 Tool 实例
     */
    private static Tool defineTool(String name, String description,
                                   Map<String, Object> properties,
                                   List<String> required) {
        // 构建 InputSchema：描述工具的输入参数格式
        var schemaBuilder = Tool.InputSchema.builder()
                .properties(JsonValue.from(properties));

        // 添加 required 字段（如果有的话）
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
     * 安全特性（与 Python 原版完全一致）：
     * <ul>
     *   <li>危险命令黑名单检查（rm -rf /、sudo、shutdown 等）</li>
     *   <li>120 秒超时限制</li>
     *   <li>输出截断到 50000 字符（防止内存溢出）</li>
     *   <li>OS 自适应：Unix 用 bash -c，Windows 用 cmd /c</li>
     * </ul>
     *
     * @param command 要执行的 shell 命令
     * @return 命令输出（stdout + stderr 合并），或错误信息
     */
    private static String runBash(String command) {
        if (command == null || command.isBlank()) {
            return "Error: command is required";
        }

        // 危险命令检查 —— 防止 Agent 执行破坏性操作
        for (String dangerous : DANGEROUS_COMMANDS) {
            if (command.contains(dangerous)) {
                return "Error: Dangerous command blocked";
            }
        }

        try {
            // OS 自适应：选择正确的 shell
            // Windows 用 cmd /c，其他系统（Linux、macOS）用 bash -c
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            // 设置工作目录为项目根目录
            pb.directory(WORKDIR.toFile());
            // 合并 stdout 和 stderr（与 Python 的 capture_output=True 行为一致）
            pb.redirectErrorStream(true);

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
                    // 提前截断：如果输出已经超过上限，停止读取
                    if (output.length() > MAX_OUTPUT) {
                        break;
                    }
                }
            }

            // 等待进程结束，设置超时
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
            // 最终截断（确保不超过上限）
            return result.length() > MAX_OUTPUT
                    ? result.substring(0, MAX_OUTPUT)
                    : result;

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 基础设施：JsonValue 转换 ====================

    /**
     * 将 SDK 的 JsonValue 递归转换为普通 Java 对象。
     * <p>
     * Anthropic SDK 使用 JsonValue 类型表示 JSON 数据，但我们在执行
     * 工具时需要普通 Java 类型（Map、List、String 等）来提取参数。
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
     * @return 转换后的普通 Java 对象（Map / List / String / Number / Boolean / null）
     */
    @SuppressWarnings("unchecked")
    private static Object jsonValueToObject(JsonValue value) {
        if (value == null) return null;

        // 优先检查 String（最常见的情况，如工具参数值）
        var strOpt = value.asString();
        if (strOpt.isPresent()) {
            return strOpt.get();
        }

        // 检查 Number
        var numOpt = value.asNumber();
        if (numOpt.isPresent()) {
            return numOpt.get();
        }

        // 检查 Boolean
        var boolOpt = value.asBoolean();
        if (boolOpt.isPresent()) {
            return boolOpt.get();
        }

        // 检查 Object（JSON 对象 → LinkedHashMap）
        // 保持插入顺序，方便调试和日志
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
        } catch (ClassCastException ignored) {
            // 某些 SDK 实现可能类型不匹配，跳过
        }

        // 检查 Array（JSON 数组 → ArrayList）
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
        } catch (ClassCastException ignored) {
            // 某些 SDK 实现可能类型不匹配，跳过
        }

        // 无法识别的类型返回 null
        return null;
    }

    // ==================== 基础设施：ANSI 颜色输出 ====================

    /**
     * 将文本包装为 ANSI 青色（用于 REPL 提示符）。
     * <p>
     * 与 Python 原版的 \033[36m 完全一致。
     *
     * @param text 要着色的文本
     * @return 带 ANSI 颜色码的字符串
     */
    private static String printCyan(String text) {
        return ANSI_CYAN + text + ANSI_RESET;
    }
}
