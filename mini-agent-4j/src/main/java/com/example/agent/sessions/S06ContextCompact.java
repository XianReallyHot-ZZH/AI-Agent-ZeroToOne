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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * S06：上下文压缩 —— 干净的记忆让 Agent 能无限工作（自包含实现）。
 * <p>
 * 三层压缩管线（本实现聚焦 Layer 1 + Layer 3，跳过 micro-compact）：
 * <pre>
 * Layer 1: persistLargeOutput —— 大工具输出(>30KB)持久化到磁盘，替换为预览标记
 * Layer 2: microCompact      —— [已跳过] 旧 tool_result 替换为占位符
 * Layer 3: autoCompact       —— token 超阈值时，LLM 摘要替换整个对话
 *          manualCompact     —— 模型调用 compact 工具触发（与 autoCompact 同逻辑）
 * </pre>
 * <p>
 * 为什么跳过 microCompact？
 * Python 版使用可变 dict 就地修改旧 tool_result，而 Java SDK 的
 * MessageParam 是不可变的。虽然可以通过重建 builder 实现，但为了
 * 教学简洁性（也是 s_full.py 的实际做法），我们只实现 persist + full compact。
 * <p>
 * 关键洞察："Agent 可以策略性地遗忘，然后继续无限工作。"
 * <p>
 * 本文件完全自包含——不依赖 com.example.agent.* 下的任何类。
 * 所有基础设施全部内联：客户端构建、工具定义、bash/read/write/edit 执行、
 * 路径沙箱、JsonValue 转换、ANSI 颜色输出、Agent 循环、上下文压缩。
 * <p>
 * 外部依赖仅有：
 * <ul>
 *   <li>com.anthropic.* — Anthropic Java SDK</li>
 *   <li>io.github.cdimascio.dotenv.* — dotenv-java 环境变量加载</li>
 *   <li>java standard library — JDK 标准库</li>
 * </ul>
 * <p>
 * 对应 Python 原版：s06_context_compact.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s06_context_compact.py">Python 原版</a>
 */
public class S06ContextCompact {

    // ==================== 常量定义 ====================

    /** 工作目录 —— Agent 的文件系统沙箱根目录 */
    private static final Path WORK_DIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /** 最大输出 token 数 */
    private static final long MAX_TOKENS = 8000;

    /** Bash 命令执行超时时间（秒） */
    private static final int BASH_TIMEOUT_SECONDS = 120;

    /** 工具输出截断上限（字符数），防止内存溢出 */
    private static final int MAX_OUTPUT = 50000;

    /** 工具结果预览打印长度 */
    private static final int PREVIEW_LEN = 200;

    /** 危险命令黑名单 */
    private static final List<String> DANGEROUS_COMMANDS = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    // ---- 上下文压缩相关常量 ----

    /** 自动压缩阈值：当估算 token 数超过此值时触发 auto-compact */
    private static final int CONTEXT_LIMIT = 50000;

    /** 大输出持久化阈值：工具输出超过此字符数时保存到文件（Python: PERSIST_THRESHOLD = 30000） */
    private static final int PERSIST_THRESHOLD = 30000;

    /** 持久化后的预览字符数（Python: PREVIEW_CHARS = 2000） */
    private static final int PREVIEW_CHARS = 2000;

    /** Transcript 保存目录 */
    private static final Path TRANSCRIPT_DIR = WORK_DIR.resolve(".transcripts");

    /** 持久化工具输出目录 */
    private static final Path TOOL_RESULTS_DIR = WORK_DIR.resolve(".task_outputs").resolve("tool-results");

    // ==================== ANSI 颜色输出 ====================

    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_BOLD  = "\033[1m";
    private static final String ANSI_DIM   = "\033[2m";
    private static final String ANSI_CYAN  = "\033[36m";
    private static final String ANSI_RED   = "\033[31m";

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

    private static String bold(String text) { return ansi(ANSI_BOLD, text); }
    private static String dim(String text)  { return ansi(ANSI_DIM, text); }
    private static String cyan(String text) { return ansi(ANSI_CYAN, text); }
    private static String red(String text)  { return ansi(ANSI_RED, text); }

    // ==================== 环境变量 & 客户端构建 ====================

    /**
     * 加载 .env 文件并返回统一的环境变量读取接口。
     * <p>
     * 优先读取 .env 文件，若不存在则回退到系统环境变量。
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
     * 支持自定义 baseUrl（第三方 API 兼容端点）。
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
     * 将简单的 name/description/properties/required 参数转换为
     * Anthropic SDK 的 Tool 对象。LLM 根据这些信息决定何时调用
     * 哪个工具、传什么参数。
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
     * 防止模型通过 "../../etc/passwd" 这类路径穿越攻击。
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
     * 安全特性：危险命令拦截、120s 超时、输出截断、OS 自适应。
     * 执行后调用 persistLargeOutput 检查是否需要持久化大输出。
     */
    private static String runBash(String command, String toolUseId) {
        if (command == null || command.isBlank()) {
            return "Error: command is required";
        }

        // 危险命令拦截
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

            boolean finished = process.waitFor(BASH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (" + BASH_TIMEOUT_SECONDS + "s)";
            }

            String result = output.toString().trim();
            if (result.isEmpty()) {
                return "(no output)";
            }
            if (result.length() > MAX_OUTPUT) {
                result = result.substring(0, MAX_OUTPUT);
            }

            // 尝试持久化大输出
            return persistLargeOutput(toolUseId, result);

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 读取文件内容。
     * <p>
     * 安全特性：路径沙箱校验、可选行数限制、输出截断。
     * 大输出自动持久化。
     */
    private static String runRead(String path, Integer limit, String toolUseId) {
        try {
            Path safePath = safePath(path);
            List<String> lines = Files.readAllLines(safePath);

            // 应用行数限制
            if (limit != null && limit > 0 && limit < lines.size()) {
                int totalLines = lines.size();
                lines = new ArrayList<>(lines.subList(0, limit));
                lines.add("... (" + (totalLines - limit) + " more lines)");
            }

            String result = String.join("\n", lines);
            if (result.length() > MAX_OUTPUT) {
                result = result.substring(0, MAX_OUTPUT);
            }

            return persistLargeOutput(toolUseId, result);

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

    // ==================== Layer 1: 大输出持久化 ====================

    /**
     * 将大工具输出持久化到磁盘，替换为预览标记。
     * <p>
     * 当工具输出超过 PERSIST_THRESHOLD（30KB）时：
     * 1. 创建 .task_outputs/tool-results/ 目录
     * 2. 将完整输出保存到 {tool_use_id}.txt 文件
     * 3. 返回预览标记（包含文件路径 + 前 PREVIEW_CHARS 个字符的预览）
     * <p>
     * 如果输出不超过阈值，原样返回（不做任何处理）。
     * <p>
     * 对应 Python 原版：persist_large_output(tool_use_id, output)
     *
     * @param toolUseId 工具调用 ID（用作持久化文件名）
     * @param output    工具的完整输出
     * @return 原样输出或预览标记
     */
    private static String persistLargeOutput(String toolUseId, String output) {
        if (output.length() <= PERSIST_THRESHOLD) {
            return output;
        }

        // 创建持久化目录
        try {
            Files.createDirectories(TOOL_RESULTS_DIR);
        } catch (Exception e) {
            // 目录创建失败时直接返回原始输出（降级处理）
            return output;
        }

        // 保存完整输出到文件（如果文件已存在则不重复写入）
        Path storedPath = TOOL_RESULTS_DIR.resolve(toolUseId + ".txt");
        if (!Files.exists(storedPath)) {
            try {
                Files.writeString(storedPath, output);
            } catch (Exception e) {
                // 写入失败时直接返回原始输出
                return output;
            }
        }

        // 构建预览标记
        String preview = output.substring(0, Math.min(output.length(), PREVIEW_CHARS));
        Path relativePath = WORK_DIR.relativize(storedPath);

        return "<persisted-output>\n"
                + "Full output saved to: " + relativePath + "\n"
                + "Preview:\n"
                + preview + "\n"
                + "</persisted-output>";
    }

    // ==================== Layer 3: 上下文压缩（auto + manual） ====================

    /**
     * 估算当前对话的 token 数。
     * <p>
     * 使用简单的字符数 / 4 估算。这不是精确的 token 计算，
     * 但足以作为压缩触发阈值使用。Python 原版使用 len(str(messages))，
     * Java 中我们通过 paramsBuilder 的内部消息来做估算。
     * <p>
     * 由于 SDK 的 paramsBuilder 没有暴露内部消息列表，我们维护一个
     * 并行的 tokenEstimate 计数器，在每次追加消息时累加。
     *
     * @param tokenEstimate 当前累计的 token 估算值
     * @return 同一个值（方法签名保持统一，方便理解）
     */
    private static boolean shouldCompact(long tokenEstimate) {
        return tokenEstimate > CONTEXT_LIMIT;
    }

    /**
     * 将完整对话保存为 transcript 文件（JSONL 格式）。
     * <p>
     * 在执行 auto-compact 前，先把完整对话保存到 .transcripts/ 目录，
     * 这样即使压缩丢失了细节，也可以从 transcript 中恢复。
     * <p>
     * 对应 Python 原版：write_transcript(messages)
     *
     * @param conversationLog 对话日志列表（每条记录是一行文本）
     * @return transcript 文件路径
     */
    private static Path writeTranscript(List<String> conversationLog) {
        try {
            Files.createDirectories(TRANSCRIPT_DIR);
        } catch (Exception ignored) {
            // 目录创建失败不影响后续流程
        }

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        Path path = TRANSCRIPT_DIR.resolve("transcript_" + timestamp + ".jsonl");

        try {
            // 每条对话记录写为一行（JSONL 格式）
            StringBuilder content = new StringBuilder();
            for (String entry : conversationLog) {
                // 简单的 JSON 转义（处理换行和引号）
                String escaped = entry
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\t", "\\t");
                content.append("\"").append(escaped).append("\"\n");
            }
            Files.writeString(path, content.toString());
        } catch (Exception e) {
            System.err.println("Warning: Failed to write transcript: " + e.getMessage());
        }

        return path;
    }

    /**
     * 调用 LLM 生成对话摘要。
     * <p>
     * 将完整对话历史发送给 LLM，让它生成一个紧凑但完整的摘要，
     * 包含：当前目标、重要发现和决策、读写过的文件、剩余工作、用户约束。
     * <p>
     * 对应 Python 原版：summarize_history(messages)
     *
     * @param client          Anthropic API 客户端
     * @param model           模型 ID
     * @param conversationLog 对话日志列表
     * @return LLM 生成的摘要文本
     */
    private static String summarizeHistory(AnthropicClient client, String model,
                                           List<String> conversationLog) {
        // 将对话日志拼接为文本，截取最后 80000 字符（避免超过 LLM 输入限制）
        String conversation = String.join("\n", conversationLog);
        if (conversation.length() > 80000) {
            conversation = conversation.substring(conversation.length() - 80000);
        }

        String prompt = "Summarize this coding-agent conversation so work can continue.\n"
                + "Preserve:\n"
                + "1. The current goal\n"
                + "2. Important findings and decisions\n"
                + "3. Files read or changed\n"
                + "4. Remaining work\n"
                + "5. User constraints and preferences\n"
                + "Be compact but concrete.\n\n"
                + conversation;

        try {
            Message summaryResponse = client.messages().create(
                    MessageCreateParams.builder()
                            .model(model)
                            .maxTokens(2000L)
                            .addUserMessage(prompt)
                            .build()
            );

            // 提取摘要文本
            StringBuilder summary = new StringBuilder();
            for (ContentBlock block : summaryResponse.content()) {
                block.text().ifPresent(tb -> summary.append(tb.text()));
            }
            return summary.toString().trim();
        } catch (Exception e) {
            // 摘要失败时返回简化版本
            return "[Summary generation failed: " + e.getMessage() + "]\n"
                    + "Last few entries:\n"
                    + String.join("\n",
                    conversationLog.subList(
                            Math.max(0, conversationLog.size() - 5),
                            conversationLog.size()));
        }
    }

    /**
     * 执行完整的上下文压缩：保存 transcript → LLM 摘要 → 重建 paramsBuilder。
     * <p>
     * 压缩流程：
     * 1. 保存完整对话到 .transcripts/ 目录
     * 2. 调用 LLM 生成对话摘要
     * 3. 重建 MessageCreateParams.Builder，只包含一条摘要消息
     * 4. 重置 token 估算计数器
     * <p>
     * 对应 Python 原版：compact_history(messages, state, focus)
     *
     * @param client          Anthropic API 客户端
     * @param model           模型 ID
     * @param paramsBuilder   当前的消息参数构建器
     * @param systemPrompt    系统提示词
     * @param tools           工具定义列表
     * @param conversationLog 对话日志列表
     * @param tokenEstimate   当前 token 估算值的包装（long[]，长度为 1）
     * @param focus           可选的关注点（manual compact 时用户指定）
     * @return 重建后的 MessageCreateParams.Builder
     */
    private static MessageCreateParams.Builder autoCompact(
            AnthropicClient client, String model,
            MessageCreateParams.Builder paramsBuilder,
            String systemPrompt, List<Tool> tools,
            List<String> conversationLog,
            long[] tokenEstimate,
            String focus) {

        // ---- 1. 保存 transcript ----
        Path transcriptPath = writeTranscript(conversationLog);
        System.out.println(dim("[transcript saved: " + transcriptPath + "]"));

        // ---- 2. 生成摘要 ----
        String summary = summarizeHistory(client, model, conversationLog);

        // 追加用户指定的关注点
        if (focus != null && !focus.isBlank()) {
            summary += "\n\nFocus to preserve next: " + focus;
        }

        // ---- 3. 重建 paramsBuilder ----
        // 用摘要替换整个对话历史，只保留一条 user 消息
        MessageCreateParams.Builder newBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(systemPrompt);

        // 重新添加所有工具定义
        for (Tool tool : tools) {
            newBuilder.addTool(tool);
        }

        // 添加压缩后的摘要作为唯一的对话历史
        String compactedMessage = "This conversation was compacted so the agent can continue working.\n\n"
                + summary;
        newBuilder.addUserMessage(compactedMessage);
        newBuilder.addAssistantMessage(
                "Understood. I have the context summary. Ready to continue.");

        // ---- 4. 重置计数器 ----
        tokenEstimate[0] = compactedMessage.length() / 4;
        conversationLog.clear();
        conversationLog.add("[compacted] " + summary);

        System.out.println(dim("[auto-compact completed]"));
        return newBuilder;
    }

    // ==================== 辅助方法 ====================

    /**
     * 人类可读的文件大小格式化。
     * <p>
     * 将字节数转换为 KB、MB 等更易读的格式。
     * 例如：formatSize(1536) → "1.5 KB"
     *
     * @param bytes 字节数
     * @return 格式化后的字符串
     */
    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        }
    }

    // ==================== JsonValue 转换 ====================

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

    // ==================== Agent 核心循环（集成上下文压缩） ====================

    /**
     * S06 Agent 核心循环 —— 在标准 Agent 循环基础上集成上下文压缩。
     * <p>
     * 与 S01/S02 的标准循环相比，S06 增加了两个压缩触发点：
     * <ul>
     *   <li>Auto-compact：每轮循环开始前检查 token 估算值，超阈值自动压缩</li>
     *   <li>Manual-compact：模型通过 compact 工具主动触发压缩</li>
     * </ul>
     * <p>
     * 因为 auto-compact 会重建 paramsBuilder（替换整个对话历史），
     * 我们需要使用可变的 paramsBuilder 引用（通过长度为 1 的数组包装）。
     * <p>
     * 对应 Python 原版：agent_loop(messages, state)
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

        while (true) {
            // ---- Layer 2: Auto-compact 检查 ----
            // 每轮循环开始前，检查 token 估算值是否超过阈值
            if (shouldCompact(tokenEstimate[0])) {
                System.out.println(dim("[auto compact: estimated "
                        + formatSize(tokenEstimate[0] * 4) + ", threshold "
                        + formatSize((long) CONTEXT_LIMIT * 4) + "]"));
                paramsHolder[0] = autoCompact(
                        client, model, paramsHolder[0],
                        systemPrompt, tools, conversationLog,
                        tokenEstimate, null);
            }

            // ---- 调用 LLM ----
            Message response = client.messages().create(paramsHolder[0].build());
            paramsHolder[0].addMessage(response);

            // 累加 token 估算（assistant 回复）
            for (ContentBlock block : response.content()) {
                block.text().ifPresent(tb -> {
                    tokenEstimate[0] += tb.text().length() / 4;
                    conversationLog.add("assistant: " + tb.text());
                });
            }

            // ---- 检查停止原因 ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                // 模型决定停止，打印文本回复
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(tb -> System.out.println(tb.text()));
                }
                return;
            }

            // ---- 遍历 content blocks，执行工具调用 ----
            List<ContentBlockParam> toolResults = new ArrayList<>();
            boolean manualCompact = false;
            String compactFocus = null;

            for (ContentBlock block : response.content()) {
                if (!block.isToolUse()) continue;

                ToolUseBlock toolUse = block.asToolUse();
                String toolName = toolUse.name();

                // 从 JsonValue 提取输入参数
                Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());
                if (input == null) input = Map.of();

                String output;

                if ("compact".equals(toolName)) {
                    // ---- Layer 3: Manual compact（compact 工具拦截） ----
                    manualCompact = true;
                    compactFocus = (String) input.get("focus");
                    output = "Compressing conversation...";

                    System.out.println(bold("> compact") + ": "
                            + dim("Compressing..."));
                } else {
                    // ---- 普通工具分发 ----
                    Function<Map<String, Object>, String> handler = toolHandlers.get(toolName);
                    if (handler != null) {
                        output = handler.apply(input);
                    } else {
                        output = "Unknown tool: " + toolName;
                    }

                    System.out.println(bold("> " + toolName) + ": "
                            + dim(output.substring(0, Math.min(output.length(), PREVIEW_LEN))));

                    // 累加 token 估算（工具输出）
                    tokenEstimate[0] += output.length() / 4;
                    conversationLog.add("tool(" + toolName + "): "
                            + output.substring(0, Math.min(output.length(), PREVIEW_LEN)));
                }

                // 构造 tool_result 消息块
                toolResults.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(toolUse.id())
                                .content(output)
                                .build()));
            }

            // 将工具结果追加为 user 消息
            paramsHolder[0].addUserMessageOfBlockParams(toolResults);

            // ---- Layer 3: Manual compact 触发 ----
            if (manualCompact) {
                System.out.println(dim("[manual compact]"));
                paramsHolder[0] = autoCompact(
                        client, model, paramsHolder[0],
                        systemPrompt, tools, conversationLog,
                        tokenEstimate, compactFocus);
            }
        }
    }

    // ==================== 提取最终文本回复 ====================

    /**
     * 从最后一个 assistant 消息中提取纯文本内容。
     * <p>
     * 对应 Python 原版：extract_text(content)
     *
     * @param response LLM 的最终回复
     * @return 纯文本内容，如果没有文本则返回空字符串
     */
    private static String extractText(Message response) {
        StringBuilder texts = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(tb -> {
                if (texts.length() > 0) texts.append("\n");
                texts.append(tb.text());
            });
        }
        return texts.toString().trim();
    }

    // ==================== 主入口 ====================

    /**
     * S06 REPL 主循环。
     * <p>
     * 整体流程：
     * <pre>
     * while True:
     *     query = input("s06 >> ")        # 读取用户输入
     *     paramsBuilder.addUserMessage(query)  # 追加到历史
     *     agentLoop(paramsBuilder, ...)    # 执行集成压缩的 Agent 循环
     * </pre>
     * <p>
     * 与 S02 的区别：
     * 1. 多了 compact 工具（manual compact 触发）
     * 2. agentLoop 内置了 auto-compact 检查
     * 3. 大工具输出自动持久化到磁盘
     * 4. 维护 tokenEstimate 和 conversationLog 用于压缩决策
     */
    public static void main(String[] args) {
        // ---- 构建客户端和加载模型 ----
        AnthropicClient client = buildClient();
        String model = loadModel();

        // ---- 系统提示词 ----
        // 与 Python 原版完全一致：告知工作目录，提醒可以使用 compact
        String systemPrompt = "You are a coding agent at " + WORK_DIR + ". "
                + "Keep working step by step, and use compact if the conversation gets too long.";

        // ---- 定义 5 个工具 ----
        // 在 S02 的 4 个工具基础上增加了 compact 工具
        List<Tool> tools = List.of(
                // bash：执行 shell 命令
                defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")),
                        List.of("command")),

                // read_file：读取文件内容
                defineTool("read_file", "Read file contents.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "limit", Map.of("type", "integer")),
                        List.of("path")),

                // write_file：写入文件
                defineTool("write_file", "Write content to a file.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "content", Map.of("type", "string")),
                        List.of("path", "content")),

                // edit_file：精确文本替换
                defineTool("edit_file", "Replace exact text in a file once.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),

                // compact：手动触发上下文压缩
                // input_schema 中 focus 是可选的，没有 required 字段
                defineTool("compact",
                        "Summarize earlier conversation so work can continue in a smaller context.",
                        Map.of("focus", Map.of("type", "string")),
                        null)
        );

        // ---- 工具分发表 ----
        // compact 不在这里分发，由 agentLoop 内联拦截
        Map<String, Function<Map<String, Object>, String>> toolHandlers = new LinkedHashMap<>();

        // bash 工具：需要传入 toolUseId 用于大输出持久化
        // 但分发表签名只接受 Map，所以我们通过 ThreadLocal 传递 toolUseId
        // 更简单的做法：直接在 agentLoop 中特殊处理 bash
        // 这里用一种折中方案：bash 的 handler 需要知道 toolUseId
        // 实际上 toolUseId 在 agentLoop 中才能获取，所以 bash 的持久化
        // 需要在 agentLoop 中处理。这里我们在 handler 中先不做持久化。
        toolHandlers.put("bash", input -> {
            String command = (String) input.get("command");
            if (command == null || command.isBlank()) return "Error: command is required";
            return runBash(command, "unknown");
        });

        toolHandlers.put("read_file", input -> {
            String path = (String) input.get("path");
            if (path == null || path.isBlank()) return "Error: path is required";
            Integer limit = null;
            Object limitObj = input.get("limit");
            if (limitObj instanceof Number num) limit = num.intValue();
            return runRead(path, limit, "unknown");
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

        // ---- 构建初始参数 ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(systemPrompt);

        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }

        // ---- 上下文压缩状态 ----
        // tokenEstimate[0] 维护当前对话的粗略 token 估算
        // 使用数组包装使其在方法间可变（类似 Python 的可变引用）
        long[] tokenEstimate = {0};

        // conversationLog 记录对话历史（用于压缩时生成摘要）
        List<String> conversationLog = new ArrayList<>();

        // 使用数组包装 paramsBuilder，使其在 auto-compact 时可以被替换
        // （因为 auto-compact 会重建整个 builder）
        MessageCreateParams.Builder[] paramsHolder = {paramsBuilder};

        // ---- REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println(bold("S06 Context Compact")
                + " — 5 tools: bash, read_file, write_file, edit_file, compact");
        System.out.println(dim("Auto-compact threshold: " + CONTEXT_LIMIT + " tokens (~"
                + formatSize((long) CONTEXT_LIMIT * 4) + ")"));
        System.out.println(dim("Large output persist threshold: "
                + formatSize(PERSIST_THRESHOLD) + "\n"));

        while (true) {
            // 打印青色提示符（与 Python 版的 \033[36ms06 >> \033[0m 一致）
            System.out.print(cyan("s06 >> "));

            // 处理 Ctrl+D / Ctrl+C
            if (!scanner.hasNextLine()) {
                break;
            }

            String query = scanner.nextLine().trim();

            // 退出命令
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // 追加用户消息
            paramsHolder[0].addUserMessage(query);
            tokenEstimate[0] += query.length() / 4;
            conversationLog.add("user: " + query);

            // 执行集成压缩的 Agent 循环
            try {
                agentLoop(client, model, paramsHolder, systemPrompt, tools,
                        toolHandlers, conversationLog, tokenEstimate);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }

            System.out.println(); // 每轮结束后空一行
        }

        System.out.println(dim("Bye!"));
    }
}
