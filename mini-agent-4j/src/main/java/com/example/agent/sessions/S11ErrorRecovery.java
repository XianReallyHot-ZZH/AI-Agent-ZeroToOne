package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * S11：错误恢复 —— 顽强的 Agent 不会因为错误而崩溃（完全自包含实现）。
 * <p>
 * 三条恢复路径的教学演示：
 *
 * <pre>
 *     LLM 响应
 *          |
 *          v
 *     [检查 stop_reason]
 *          |
 *          +-- "max_tokens" ----> [策略 1：max_output_tokens 恢复]
 *          |                       注入续接消息：
 *          |                       "Output limit hit. Continue directly."
 *          |                       最多重试 MAX_RECOVERY_ATTEMPTS（3）次。
 *          |                       计数器：maxOutputRecoveryCount
 *          |
 *          +-- API 错误 --------> [检查错误类型]
 *          |                       |
 *          |                       +-- prompt_too_long --> [策略 2：压缩 + 重试]
 *          |                       |   触发 autoCompact（LLM 摘要）。
 *          |                       |   用摘要替换历史。
 *          |                       |   重试本轮。
 *          |                       |
 *          |                       +-- 连接/限速错误 --> [策略 3：退避重试]
 *          |                           指数退避：base * 2^attempt + random(0,1)
 *          |                           最多 3 次重试。
 *          |
 *          +-- "end_turn" ------> [正常退出]
 *
 *     恢复优先级（首次匹配生效）：
 *     1. max_tokens -> 注入续接消息，重试
 *     2. prompt_too_long -> 压缩，重试
 *     3. 连接错误 -> 退避，重试
 *     4. 所有重试耗尽 -> 优雅失败
 * </pre>
 * <p>
 * 关键洞察："一个健壮的 Agent 会自动恢复，而不是崩溃退出。"
 * <p>
 * 本文件完全自包含——不依赖 com.example.agent.* 下的任何类。
 * 所有基础设施全部内联：客户端构建、工具定义、bash/read/write/edit 执行、
 * 路径沙箱、JsonValue 转换、ANSI 颜色输出、Agent 循环（含错误恢复）、
 * autoCompact 上下文压缩、指数退避重试。
 * <p>
 * 外部依赖仅有：
 * <ul>
 *   <li>com.anthropic.* — Anthropic Java SDK</li>
 *   <li>io.github.cdimascio.dotenv.* — dotenv-java 环境变量加载</li>
 *   <li>java standard library — JDK 标准库</li>
 * </ul>
 * <p>
 * 对应 Python 原版：s11_error_recovery.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s11_error_recovery.py">Python 原版</a>
 */
public class S11ErrorRecovery {

    // ==================== 常量 ====================

    /** 最大输出长度（字符），与 Python 原版 50000 对齐 */
    private static final int MAX_OUTPUT = 50000;

    /** bash 命令超时（秒），与 Python 原版 120s 对齐 */
    private static final int BASH_TIMEOUT = 120;

    /** 危险命令黑名单，防止模型执行破坏性操作 */
    private static final List<String> DANGEROUS_COMMANDS = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    /** 工作目录（Agent 的文件操作沙箱根目录） */
    private static final Path WORK_DIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /** 系统提示词：告诉模型它是一个编码 Agent，需要用工具完成任务 */
    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + WORK_DIR + ". Use tools to solve tasks.";

    /** 最大 LLM 输出 token 数 */
    private static final long MAX_TOKENS = 8000;

    // ---- 错误恢复常量 ----

    /** 最大恢复尝试次数（max_tokens 恢复和连接重试共用） */
    private static final int MAX_RECOVERY_ATTEMPTS = 3;

    /** 退避基础延迟（秒） */
    private static final double BACKOFF_BASE_DELAY = 1.0;

    /** 退避最大延迟（秒） */
    private static final double BACKOFF_MAX_DELAY = 30.0;

    /** 主动压缩的 token 估算阈值（字符数 / 4 约等于 token 数） */
    private static final int TOKEN_THRESHOLD = 50000;

    /** max_tokens 恢复时注入的续接消息 */
    private static final String CONTINUATION_MESSAGE =
            "Output limit hit. Continue directly from where you stopped -- "
            + "no recap, no repetition. Pick up mid-sentence if needed.";

    // ==================== ANSI 颜色输出 ====================

    private static final String ANSI_RESET  = "\033[0m";
    private static final String ANSI_BOLD   = "\033[1m";
    private static final String ANSI_DIM    = "\033[2m";
    private static final String ANSI_CYAN   = "\033[36m";
    private static final String ANSI_RED    = "\033[31m";
    private static final String ANSI_YELLOW = "\033[33m";

    /** 检测终端是否支持 ANSI 转义码 */
    private static final boolean ANSI_SUPPORTED = detectAnsi();

    private static boolean detectAnsi() {
        String term = System.getenv("TERM");
        if (term != null && !term.isEmpty()) return true;
        if (System.getenv("WT_SESSION") != null) return true;
        if ("ON".equalsIgnoreCase(System.getenv("ConEmuANSI"))) return true;
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /** 应用 ANSI 颜色，不支持时原样返回 */
    private static String ansi(String code, String text) {
        return ANSI_SUPPORTED ? code + text + ANSI_RESET : text;
    }

    private static String bold(String text)   { return ansi(ANSI_BOLD, text); }
    private static String dim(String text)    { return ansi(ANSI_DIM, text); }
    private static String cyan(String text)   { return ansi(ANSI_CYAN, text); }
    private static String red(String text)    { return ansi(ANSI_RED, text); }
    private static String yellow(String text) { return ansi(ANSI_YELLOW, text); }

    // ==================== 环境变量 & 客户端构建 ====================

    /**
     * 加载 .env 文件并返回统一的环境变量读取接口。
     * <p>
     * 优先读取 .env 文件，若不存在则回退到系统环境变量。
     * 对应 Python 原版顶部的 load_dotenv(override=True)。
     */
    private static Dotenv loadDotenv() {
        return new DotenvBuilder()
                .ignoreIfMissing()
                .systemProperties()
                .load();
    }

    /**
     * 构建 Anthropic API 客户端。
     * <p>
     * 支持自定义 baseUrl（用于第三方 API 兼容端点，如 OpenRouter）。
     * 如果设置了 ANTHROPIC_BASE_URL，则清除 ANTHROPIC_AUTH_TOKEN 避免冲突。
     */
    private static AnthropicClient buildClient() {
        Dotenv dotenv = loadDotenv();

        String baseUrl = dotenv.get("ANTHROPIC_BASE_URL");
        String apiKey = dotenv.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY 未配置。请在 .env 文件或系统环境变量中设置。");
        }

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

    /**
     * 从环境变量加载模型 ID。
     * <p>
     * 对应 Python 原版的 MODEL = os.environ["MODEL_ID"]。
     */
    private static String loadModel() {
        Dotenv dotenv = loadDotenv();
        String model = dotenv.get("MODEL_ID");
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "MODEL_ID 未配置。请在 .env 文件或系统环境变量中设置。");
        }
        return model;
    }

    // ==================== 工具定义辅助 ====================

    /**
     * 构建一个 SDK Tool 定义。
     * <p>
     * 将简单的 name/description/properties/required 参数转换为 Anthropic SDK 的 Tool 对象。
     * 这是所有工具定义的统一入口。
     *
     * @param name        工具名称（模型调用时使用）
     * @param description 工具描述（告诉模型工具的用途）
     * @param properties  JSON Schema 属性定义
     * @param required    必需属性列表，null 或空列表表示无必需属性
     * @return 构建好的 Tool 对象
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

    // ==================== 路径沙箱 ====================

    /**
     * 路径安全校验：确保文件操作不会逃逸出工作目录。
     * <p>
     * 防止模型通过 "../../etc/passwd" 这类路径穿越攻击读取或修改系统文件。
     *
     * @param relativePath 相对路径字符串
     * @return 安全的绝对路径
     * @throws SecurityException 如果路径逃逸出工作目录
     */
    private static Path safePath(String relativePath) {
        Path resolved = WORK_DIR.resolve(relativePath).normalize().toAbsolutePath();
        if (!resolved.startsWith(WORK_DIR)) {
            throw new SecurityException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }

    // ==================== 工具实现 ====================

    /**
     * 执行 shell 命令。
     * <p>
     * 安全特性：危险命令黑名单检查、120 秒超时、输出截断、OS 自适应。
     * <p>
     * 对应 Python 原版：run_bash(command) 函数。
     *
     * @param command 要执行的 shell 命令
     * @return 命令输出（stdout + stderr 合并）
     */
    private static String runBash(String command) {
        for (String dangerous : DANGEROUS_COMMANDS) {
            if (command.contains(dangerous)) {
                return "Error: Dangerous command blocked";
            }
        }

        try {
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            pb.directory(WORK_DIR.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append("\n");
                    }
                    output.append(line);
                    if (output.length() > MAX_OUTPUT) {
                        break;
                    }
                }
            }

            boolean finished = process.waitFor(BASH_TIMEOUT, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (" + BASH_TIMEOUT + "s)";
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

    /**
     * 读取文件内容。
     * <p>
     * 安全特性：路径沙箱校验、可选行数限制、输出截断。
     * <p>
     * 对应 Python 原版：run_read(path, limit) 函数。
     *
     * @param path  相对文件路径
     * @param limit 最大读取行数，null 表示读取全部
     * @return 文件内容字符串
     */
    private static String runRead(String path, Integer limit) {
        try {
            Path safePath = safePath(path);
            List<String> lines = Files.readAllLines(safePath);

            if (limit != null && limit > 0 && limit < lines.size()) {
                int totalLines = lines.size();
                lines = new ArrayList<>(lines.subList(0, limit));
                lines.add("... (" + (totalLines - limit) + " more lines)");
            }

            String result = String.join("\n", lines);
            return result.length() > MAX_OUTPUT
                    ? result.substring(0, MAX_OUTPUT)
                    : result;

        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 写入文件内容。
     * <p>
     * 安全特性：路径沙箱校验、自动创建父目录。
     * <p>
     * 对应 Python 原版：run_write(path, content) 函数。
     *
     * @param path    相对文件路径
     * @param content 要写入的内容
     * @return 操作结果描述
     */
    private static String runWrite(String path, String content) {
        try {
            Path safePath = safePath(path);
            Files.createDirectories(safePath.getParent());
            Files.writeString(safePath, content);
            return "Wrote " + content.length() + " bytes to " + path;
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 精确文本替换（只替换第一次出现的位置）。
     * <p>
     * 使用 Pattern.quote() 确保 old_text 作为字面量匹配。
     * <p>
     * 对应 Python 原版：run_edit(path, old_text, new_text) 函数。
     *
     * @param path    相对文件路径
     * @param oldText 要查找的文本
     * @param newText 替换后的文本
     * @return 操作结果描述
     */
    private static String runEdit(String path, String oldText, String newText) {
        try {
            Path safePath = safePath(path);
            String content = Files.readString(safePath);

            if (!content.contains(oldText)) {
                return "Error: Text not found in " + path;
            }

            String updated = content.replaceFirst(
                    java.util.regex.Pattern.quote(oldText),
                    java.util.regex.Matcher.quoteReplacement(newText));
            Files.writeString(safePath, updated);
            return "Edited " + path;

        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================== JsonValue 转换 ====================

    /**
     * 将 SDK 的 JsonValue 递归转换为普通 Java 对象。
     * <p>
     * Anthropic SDK 返回的工具输入是 JsonValue 类型，
     * 我们需要递归地将其转换为 Map/List/String/Number/Boolean 等 Java 原生类型。
     *
     * @param value JsonValue 实例
     * @return 对应的 Java 原生对象
     */
    @SuppressWarnings("unchecked")
    private static Object jsonValueToObject(JsonValue value) {
        if (value == null) return null;

        var strOpt = value.asString();
        if (strOpt.isPresent()) return strOpt.get();

        var numOpt = value.asNumber();
        if (numOpt.isPresent()) return numOpt.get();

        var boolOpt = value.asBoolean();
        if (boolOpt.isPresent()) return boolOpt.get();

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

    // ==================== 错误恢复：估算 token 数 ====================

    /**
     * 粗略估算当前对话的 token 数。
     * <p>
     * 采用简单的字符数 / 4 估算方式（与 Python 原版一致）。
     * 由于 Java SDK 的 paramsBuilder 没有暴露内部消息列表，
     * 我们通过维护一个并行的 tokenEstimate 计数器来实现。
     * <p>
     * 对应 Python 原版：estimate_tokens(messages) 函数。
     *
     * @param tokenEstimate 当前累计的 token 估算值（long[]，长度为 1）
     * @return 是否超过阈值
     */
    private static boolean isOverTokenThreshold(long[] tokenEstimate) {
        return tokenEstimate[0] > TOKEN_THRESHOLD;
    }

    // ==================== 错误恢复：指数退避 ====================

    /**
     * 计算带抖动的指数退避延迟。
     * <p>
     * 公式：min(base * 2^attempt, maxDelay) + random(0, 1)
     * <p>
     * 抖动（jitter）可以防止多个客户端在同一时刻集中重试（惊群效应）。
     * <p>
     * 对应 Python 原版：backoff_delay(attempt) 函数。
     *
     * @param attempt 当前重试次数（从 0 开始）
     * @return 延迟秒数
     */
    private static double backoffDelay(int attempt) {
        double delay = Math.min(BACKOFF_BASE_DELAY * Math.pow(2, attempt), BACKOFF_MAX_DELAY);
        double jitter = ThreadLocalRandom.current().nextDouble(0, 1);
        return delay + jitter;
    }

    // ==================== 错误恢复：autoCompact ====================

    /**
     * 将对话历史压缩为一条简短的续接摘要。
     * <p>
     * 流程：
     * 1. 将对话日志拼接为文本（截取最后 80000 字符，避免超限）
     * 2. 调用 LLM 生成包含任务概览、当前状态、关键决策和剩余步骤的摘要
     * 3. 用摘要重建 paramsBuilder（替换整个历史）
     * 4. 重置 token 估算计数器
     * <p>
     * 对应 Python 原版：auto_compact(messages) 函数。
     *
     * @param client          Anthropic API 客户端
     * @param model           模型 ID
     * @param paramsBuilder   当前的消息参数构建器
     * @param systemPrompt    系统提示词
     * @param tools           工具定义列表
     * @param conversationLog 对话日志列表
     * @param tokenEstimate   token 估算值的包装（long[]，长度为 1）
     * @return 重建后的 MessageCreateParams.Builder
     */
    private static MessageCreateParams.Builder autoCompact(
            AnthropicClient client, String model,
            MessageCreateParams.Builder paramsBuilder,
            String systemPrompt, List<Tool> tools,
            List<String> conversationLog,
            long[] tokenEstimate) {

        // 将对话日志拼接为文本，截取最后 80000 字符
        String conversationText = String.join("\n", conversationLog);
        if (conversationText.length() > 80000) {
            conversationText = conversationText.substring(conversationText.length() - 80000);
        }

        // 构造摘要提示词（与 Python 原版一致）
        String prompt = "Summarize this conversation for continuity. Include:\n"
                + "1) Task overview and success criteria\n"
                + "2) Current state: completed work, files touched\n"
                + "3) Key decisions and failed approaches\n"
                + "4) Remaining next steps\n"
                + "Be concise but preserve critical details.\n\n"
                + conversationText;

        // 调用 LLM 生成摘要
        String summary;
        try {
            Message summaryResponse = client.messages().create(
                    MessageCreateParams.builder()
                            .model(model)
                            .maxTokens(4000L)
                            .addUserMessage(prompt)
                            .build()
            );

            StringBuilder sb = new StringBuilder();
            for (ContentBlock block : summaryResponse.content()) {
                block.text().ifPresent(tb -> sb.append(tb.text()));
            }
            summary = sb.toString().trim();
        } catch (Exception e) {
            // 摘要生成失败时的降级处理
            summary = "(compact failed: " + e.getMessage() + "). Previous context lost.";
        }

        // 构造续接消息
        String continuation = "This session continues from a previous conversation that was compacted. "
                + "Summary of prior context:\n\n" + summary + "\n\n"
                + "Continue from where we left off without re-asking the user.";

        // 重建 paramsBuilder：只包含摘要这一条 user 消息
        MessageCreateParams.Builder newBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(systemPrompt);

        for (Tool tool : tools) {
            newBuilder.addTool(tool);
        }

        newBuilder.addUserMessage(continuation);

        // 重置计数器
        tokenEstimate[0] = continuation.length() / 4;
        conversationLog.clear();
        conversationLog.add("[compacted] " + summary);

        System.out.println(dim("[auto-compact completed]"));
        return newBuilder;
    }

    // ==================== 错误恢复：判断 prompt 过长 ====================

    /**
     * 判断异常是否为 prompt 过长错误。
     * <p>
     * 检测逻辑与 Python 原版一致：
     * - 错误信息包含 "overlong_prompt"
     * - 或同时包含 "prompt" 和 "long"
     * <p>
     * 对应 Python 原版中的条件：
     * <pre>
     * if "overlong_prompt" in error_body or ("prompt" in error_body and "long" in error_body)
     * </pre>
     *
     * @param e API 调用抛出的异常
     * @return 是否为 prompt 过长错误
     */
    private static boolean isPromptTooLong(Exception e) {
        String errorBody = e.getMessage();
        if (errorBody == null) return false;
        String lower = errorBody.toLowerCase();
        return lower.contains("overlong_prompt")
                || (lower.contains("prompt") && lower.contains("long"));
    }

    // ==================== Agent 核心循环（含错误恢复） ====================

    /**
     * S11 Agent 核心循环 —— 在标准循环基础上集成三条错误恢复路径。
     * <p>
     * 循环结构（与 S01/S02 的标准循环有本质区别）：
     * <pre>
     * while (true) {
     *     // 外层：带重试的 API 调用
     *     Message response = null;
     *     for (attempt = 0; attempt <= MAX_RECOVERY; attempt++) {
     *         try {
     *             response = LLM(params);
     *             break;  // 成功
     *         } catch (Exception e) {
     *             if (isPromptTooLong(e))  -> autoCompact, continue
     *             if (attempt < MAX)        -> sleep(backoff), continue
     *             else                      -> return（重试耗尽）
     *         }
     *     }
     *
     *     // 策略 1：max_tokens 恢复
     *     if (stopReason == MAX_TOKENS) {
     *         if (count < MAX) -> 注入续接消息, continue
     *         else             -> return（恢复耗尽）
     *     }
     *
     *     // 正常工具执行...
     *
     *     // 主动 auto-compact 检查
     *     if (estimateTokens > TOKEN_THRESHOLD) -> autoCompact
     * }
     * </pre>
     * <p>
     * 对应 Python 原版：agent_loop(messages) 函数。
     *
     * @param client           Anthropic API 客户端
     * @param model            模型 ID
     * @param paramsHolder     消息参数构建器的可变引用（长度为 1 的数组）
     * @param systemPrompt     系统提示词
     * @param tools            工具定义列表
     * @param toolHandlers     工具分发表
     * @param conversationLog  对话日志列表
     * @param tokenEstimate    token 估算值（long[]，长度为 1，可变）
     */
    @SuppressWarnings("unchecked")
    private static void agentLoop(
            AnthropicClient client, String model,
            MessageCreateParams.Builder[] paramsHolder,
            String systemPrompt, List<Tool> tools,
            Map<String, Function<Map<String, Object>, String>> toolHandlers,
            List<String> conversationLog,
            long[] tokenEstimate) {

        // max_tokens 恢复计数器（与 Python 原版 max_output_recovery_count 对应）
        int maxOutputRecoveryCount = 0;

        while (true) {
            // ---- 带重试的 API 调用 ----
            // 将 API 调用包裹在重试循环中，处理连接错误和 prompt 过长
            Message response = null;

            for (int attempt = 0; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
                try {
                    response = client.messages().create(paramsHolder[0].build());
                    break; // 成功，跳出重试循环

                } catch (Exception e) {
                    // ---- 策略 2：prompt_too_long -> 压缩并重试 ----
                    if (isPromptTooLong(e)) {
                        System.out.println(yellow("[Recovery] Prompt too long. Compacting... "
                                + "(attempt " + (attempt + 1) + ")"));
                        paramsHolder[0] = autoCompact(
                                client, model, paramsHolder[0],
                                systemPrompt, tools, conversationLog, tokenEstimate);
                        continue; // 压缩后重试
                    }

                    // ---- 策略 3：网络错误（Connection/Timeout）-> 指数退避重试 ----
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    boolean isNetworkError = msg.contains("Connection")
                            || msg.contains("Timeout")
                            || msg.contains("timeout");
                    if (isNetworkError) {
                        if (attempt < MAX_RECOVERY_ATTEMPTS) {
                            double delay = backoffDelay(attempt);
                            System.out.println(yellow("[Recovery] Network error: " + e.getMessage()
                                    + ". Retrying in " + String.format("%.1f", delay) + "s"
                                    + " (attempt " + (attempt + 1) + "/" + MAX_RECOVERY_ATTEMPTS + ")"));
                            try {
                                Thread.sleep((long) (delay * 1000));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                System.err.println(red("[Error] Interrupted during backoff."));
                                return;
                            }
                            continue;
                        }

                        // 网络错误重试耗尽
                        System.err.println(red("[Error] Network error persisted after "
                                + MAX_RECOVERY_ATTEMPTS + " retries: " + e.getMessage()));
                        return;
                    }

                    // ---- 其他异常（编程 bug）-> 不重试，直接抛出 ----
                    throw new RuntimeException("Unexpected error (likely a bug): " + e.getMessage(), e);
                }
            }

            if (response == null) {
                System.err.println(red("[Error] No response received."));
                return;
            }

            // 将 assistant 回复追加到历史
            paramsHolder[0].addMessage(response);

            // 累加 token 估算（assistant 回复）
            for (ContentBlock block : response.content()) {
                block.text().ifPresent(tb -> {
                    tokenEstimate[0] += tb.text().length() / 4;
                    conversationLog.add("assistant: " + tb.text());
                });
            }

            // ---- 策略 1：max_tokens 恢复 ----
            // 当 LLM 输出被截断（stopReason == MAX_TOKENS）时，注入续接消息让它继续
            boolean isMaxTokens = response.stopReason()
                    .map(StopReason.MAX_TOKENS::equals)
                    .orElse(false);

            if (isMaxTokens) {
                maxOutputRecoveryCount++;
                if (maxOutputRecoveryCount <= MAX_RECOVERY_ATTEMPTS) {
                    System.out.println(yellow("[Recovery] max_tokens hit "
                            + "(" + maxOutputRecoveryCount + "/" + MAX_RECOVERY_ATTEMPTS + "). "
                            + "Injecting continuation..."));
                    paramsHolder[0].addUserMessage(CONTINUATION_MESSAGE);
                    tokenEstimate[0] += CONTINUATION_MESSAGE.length() / 4;
                    conversationLog.add("user: [continuation]");
                    continue; // 注入续接消息后重试循环
                } else {
                    System.err.println(red("[Error] max_tokens recovery exhausted "
                            + "(" + MAX_RECOVERY_ATTEMPTS + " attempts). Stopping."));
                    return;
                }
            }

            // 成功响应（非 max_tokens）时重置计数器
            maxOutputRecoveryCount = 0;

            // ---- 检查是否需要继续执行工具 ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                // 模型决定停止，打印文本回复给用户
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(textBlock ->
                            System.out.println(textBlock.text()));
                }
                return;
            }

            // ---- 遍历 content blocks，执行工具调用 ----
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    String toolName = toolUse.name();

                    Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());
                    if (input == null) input = Map.of();

                    // 从分发表查找并执行对应的工具处理函数
                    Function<Map<String, Object>, String> handler = toolHandlers.get(toolName);
                    String output;
                    try {
                        if (handler != null) {
                            output = handler.apply(input);
                        } else {
                            output = "Unknown tool: " + toolName;
                        }
                    } catch (Exception e) {
                        output = "Error: " + e.getMessage();
                    }

                    // 打印工具调用日志
                    System.out.println(bold("> " + toolName) + ": "
                            + dim(output.substring(0, Math.min(output.length(), 200))));

                    // 累加 token 估算（工具输出）
                    tokenEstimate[0] += output.length() / 4;
                    conversationLog.add("tool(" + toolName + "): "
                            + output.substring(0, Math.min(output.length(), 200)));

                    // 构造 tool_result 消息块
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                }
            }

            // 将工具结果追加为 user 消息
            paramsHolder[0].addUserMessageOfBlockParams(toolResults);

            // ---- 主动 auto-compact 检查 ----
            // 每轮工具执行后检查 token 估算，超阈值则主动压缩
            if (isOverTokenThreshold(tokenEstimate)) {
                System.out.println(yellow("[Recovery] Token estimate exceeds threshold. Auto-compacting..."));
                paramsHolder[0] = autoCompact(
                        client, model, paramsHolder[0],
                        systemPrompt, tools, conversationLog, tokenEstimate);
            }
        }
    }

    // ==================== 主程序入口 ====================

    /**
     * REPL 主循环：读取用户输入 → 追加到对话历史 → 执行含错误恢复的 Agent 循环。
     * <p>
     * 整体流程：
     * <pre>
     * while True:
     *     query = input("s11 >> ")     # 读取用户输入
     *     paramsBuilder.addUserMessage(query)  # 追加到历史
     *     agent_loop(paramsBuilder, ...)  # 执行含错误恢复的 Agent 循环
     * </pre>
     * <p>
     * 与 S02 的区别是 Agent 循环内集成了三条错误恢复路径：
     * 1. max_tokens 恢复：注入续接消息
     * 2. prompt_too_long 恢复：autoCompact 压缩
     * 3. 连接/限速错误恢复：指数退避重试
     */
    public static void main(String[] args) {
        // ---- 构建客户端和加载模型 ----
        AnthropicClient client = buildClient();
        String model = loadModel();

        // ---- 定义 4 个工具 ----
        List<Tool> tools = List.of(
                // bash：执行 shell 命令
                defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")),
                        List.of("command")),

                // read_file：读取文件内容（limit 可选）
                defineTool("read_file", "Read file contents.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "limit", Map.of("type", "integer")),
                        List.of("path")),

                // write_file：写入文件（自动创建父目录）
                defineTool("write_file", "Write content to file.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "content", Map.of("type", "string")),
                        List.of("path", "content")),

                // edit_file：精确文本替换
                defineTool("edit_file", "Replace exact text in file.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text"))
        );

        // ---- 工具分发表：工具名 → 处理函数 ----
        Map<String, Function<Map<String, Object>, String>> toolHandlers = new LinkedHashMap<>();
        toolHandlers.put("bash", input -> {
            String command = (String) input.get("command");
            if (command == null || command.isBlank()) return "Error: command is required";
            return runBash(command);
        });
        toolHandlers.put("read_file", input -> {
            String path = (String) input.get("path");
            if (path == null || path.isBlank()) return "Error: path is required";
            Integer limit = null;
            Object limitObj = input.get("limit");
            if (limitObj instanceof Number num) limit = num.intValue();
            return runRead(path, limit);
        });
        toolHandlers.put("write_file", input -> {
            String path = (String) input.get("path");
            String content = (String) input.get("content");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (content == null) return "Error: content is required";
            return runWrite(path, content);
        });
        toolHandlers.put("edit_file", input -> {
            String path = (String) input.get("path");
            String oldText = (String) input.get("old_text");
            String newText = (String) input.get("new_text");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (oldText == null) return "Error: old_text is required";
            if (newText == null) return "Error: new_text is required";
            return runEdit(path, oldText, newText);
        });

        // ---- 构建消息参数 ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM_PROMPT);

        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }

        // ---- 错误恢复状态 ----
        // tokenEstimate[0] 维护当前对话的粗略 token 估算
        long[] tokenEstimate = {0};

        // conversationLog 记录对话历史（用于 autoCompact 时生成摘要）
        List<String> conversationLog = new ArrayList<>();

        // 使用数组包装 paramsBuilder，使其在 auto-compact 时可以被替换
        MessageCreateParams.Builder[] paramsHolder = {paramsBuilder};

        // ---- REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println(bold("S11 Error Recovery")
                + " — max_tokens / prompt_too_long / connection backoff");
        System.out.println(dim("Recovery attempts: " + MAX_RECOVERY_ATTEMPTS
                + ", backoff base: " + BACKOFF_BASE_DELAY + "s"
                + ", max: " + BACKOFF_MAX_DELAY + "s"
                + ", token threshold: " + TOKEN_THRESHOLD));
        System.out.println("Type 'q' or 'exit' to quit.\n");

        while (true) {
            // 打印提示符（青色 "s11 >>"）
            System.out.print(cyan("s11 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // 追加用户消息到对话历史
            paramsHolder[0].addUserMessage(query);
            tokenEstimate[0] += query.length() / 4;
            conversationLog.add("user: " + query);

            // 执行含错误恢复的 Agent 循环
            try {
                agentLoop(client, model, paramsHolder, SYSTEM_PROMPT, tools,
                        toolHandlers, conversationLog, tokenEstimate);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }
            System.out.println(); // 每轮结束后空一行，视觉分隔
        }

        System.out.println(dim("Bye!"));
    }
}
