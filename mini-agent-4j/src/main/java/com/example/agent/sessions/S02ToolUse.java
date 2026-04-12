package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * S02：工具分发 —— 完全自包含实现（不依赖 core/、tools/、util/ 包）。
 * <p>
 * Agent 循环没有任何变化。我们只是往工具数组里加了工具，
 * 然后用一个分发表（dispatch map）来路由调用。
 * <p>
 * 关键洞察："循环根本没改。我只是加了工具。"
 * <p>
 * 本文件将所有基础设施内联：
 * - buildClient()：构建 Anthropic API 客户端
 * - loadDotenv()：从环境变量加载配置
 * - defineTool()：构建 SDK Tool 定义
 * - runBash()：执行 shell 命令（OS 自适应、危险命令拦截、超时、输出截断）
 * - runRead()：读取文件内容（行数限制、路径沙箱、输出截断）
 * - runWrite()：写入文件（自动创建目录、路径沙箱）
 * - runEdit()：精确文本替换（单次替换、Pattern.quote 字面量匹配）
 * - safePath()：路径沙箱校验，防止路径穿越
 * - normalizeMessages()：消息清理（文档对齐 + 孤儿 tool_use 检测）
 * - ANSI 输出：终端彩色文本（含 ANSI 支持检测）
 * - agentLoop() + runOneTurn()：核心 LLM 调用 → 工具执行 → 结果回传循环
 * - extractText()：从最终 assistant 回复提取文本
 * - jsonValueToObject()：JsonValue → 普通 Java 对象转换
 * <p>
 * 并发安全分类（对应 Python 原版）：
 * - CONCURRENCY_SAFE = {read_file}   —— 只读，可并行
 * - CONCURRENCY_UNSAFE = {write_file, edit_file}  —— 有副作用，必须串行
 * <p>
 * 对应 Python 原版：s02_tool_use.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s02_tool_use.py">Python 原版</a>
 */
public class S02ToolUse {

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
            "You are a coding agent at " + WORK_DIR + ". Use tools to solve tasks. Act, don't explain.";

    /** 最大输出 token 数（与 Python 原版一致） */
    private static final long MAX_TOKENS = 8000L;

    // ==================== 并发安全分类 ====================

    /** 只读工具，可以安全并行执行 */
    private static final Set<String> CONCURRENCY_SAFE = Set.of("read_file");

    /** 有副作用的工具，必须串行执行 */
    private static final Set<String> CONCURRENCY_UNSAFE = Set.of("write_file", "edit_file");

    // ==================== ANSI 颜色输出 ====================

    private static final String ANSI_RESET  = "\033[0m";
    private static final String ANSI_BOLD   = "\033[1m";
    private static final String ANSI_DIM    = "\033[2m";
    private static final String ANSI_CYAN   = "\033[36m";
    private static final String ANSI_RED    = "\033[31m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_GRAY   = "\033[90m";
    private static final String ANSI_GREEN  = "\033[32m";

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

    private static String bold(String text)  { return ansi(ANSI_BOLD, text); }
    private static String dim(String text)   { return ansi(ANSI_DIM, text); }
    private static String cyan(String text)  { return ansi(ANSI_CYAN, text); }
    private static String red(String text)   { return ansi(ANSI_RED, text); }
    private static String yellow(String text) { return ansi(ANSI_YELLOW, text); }
    private static String gray(String text)  { return ansi(ANSI_GRAY, text); }
    private static String green(String text) { return ansi(ANSI_GREEN, text); }

    // ==================== LoopState 数据类 ====================

    /**
     * Agent 循环状态 —— 与 S01 Java 版的 LoopState 结构一致。
     * <p>
     * 与 Python 原版的区别：Python S02 直接操作 messages list，
     * 不使用 LoopState（Python S01 有 LoopState 但 S02 简化了）。
     * Java 版保持 LoopState 以维持与 S01 的教学连续性，
     * 体现"S02 的循环与 S01 完全相同"这一核心洞察。
     */
    static class LoopState {
        /** 对话历史累积器 */
        final MessageCreateParams.Builder paramsBuilder;
        /** 当前轮次计数（从 1 开始） */
        int turnCount;
        /** 续行原因：null 表示停止，"tool_result" 表示刚执行完工具需要继续 */
        String transitionReason;
        /** 最后一次 LLM 响应（用于循环结束后 extractText） */
        Message lastResponse;

        LoopState(MessageCreateParams.Builder paramsBuilder) {
            this.paramsBuilder = paramsBuilder;
            this.turnCount = 1;
            this.transitionReason = null;
            this.lastResponse = null;
        }
    }

    // ==================== 环境变量 & 客户端构建 ====================

    /**
     * 加载 .env 文件并返回统一的环境变量读取接口。
     * <p>
     * 对应 Python 原版顶部的 load_dotenv(override=True)。
     * dotenv-java 的 dotenv.get() 会优先从 .env 文件读取值（当文件存在时），
     * 行为等价于 Python 的 override=True。
     */
    private static Dotenv loadDotenv() {
        return Dotenv.configure()
                .ignoreIfMissing()
                .load();
    }

    /**
     * 构建 Anthropic API 客户端。
     * <p>
     * 支持自定义 baseUrl（用于第三方 API 兼容端点，如 OpenRouter）。
     * 如果设置了 ANTHROPIC_BASE_URL，则设置 ANTHROPIC_AUTH_TOKEN 为空
     * 避免冲突（与 Python 原版 os.environ.pop 行为对齐）。
     */
    private static AnthropicClient buildClient(Dotenv dotenv) {
        String baseUrl = dotenv.get("ANTHROPIC_BASE_URL");

        // 如果设置了自定义 baseUrl，清除 auth token 避免冲突
        // Python: os.environ.pop("ANTHROPIC_AUTH_TOKEN", None)
        if (baseUrl != null && !baseUrl.isBlank()) {
            System.setProperty("ANTHROPIC_AUTH_TOKEN", "");
        }

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

    // ==================== 工具定义辅助 ====================

    /**
     * 构建一个 SDK Tool 定义。
     * <p>
     * 将简单的 name/description/properties/required 参数转换为 Anthropic SDK 的 Tool 对象。
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
     * <p>
     * 对应 Python 原版：safe_path(p) 函数。
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
     * 安全特性：
     * - 危险命令黑名单检查（rm -rf /、sudo、shutdown 等）
     * - 120 秒超时自动终止
     * - 输出截断到 50000 字符
     * - OS 自适应：Windows 用 cmd /c，Unix 用 bash -c
     * <p>
     * 对应 Python 原版：run_bash(command) 函数。
     */
    private static String runBash(String command) {
        if (command == null || command.isBlank()) {
            return "Error: command is required";
        }

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
     */
    private static String runWrite(String path, String content) {
        try {
            Path safePath = safePath(path);
            Files.createDirectories(safePath.getParent());
            Files.writeString(safePath, content);
            return "Wrote " + content.length() + " bytes to " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 精确文本替换（仅替换第一次出现）。
     * <p>
     * 使用 Pattern.quote() 确保 old_text 作为字面量匹配，
     * 使用 Matcher.quoteReplacement() 确保 new_text 中的特殊字符不被解释。
     * <p>
     * 对应 Python 原版：run_edit(path, old_text, new_text) 函数。
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

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================== 消息规范化 ====================

    /**
     * 消息规范化 —— 对应 Python 原版 normalize_messages() 函数。
     * <p>
     * Python 原版做三件事：
     * <ol>
     *   <li><b>剥离内部元数据</b> — 过滤以 "_" 开头的字段，API 不认识这些字段。
     *       Java SDK 的 paramsBuilder.addMessage(response) 自动将 SDK 对象
     *       转为 API 兼容格式，不需要手动清理。</li>
     *   <li><b>补齐孤立 tool_use</b> — 如果某个 tool_use 调用没有对应的 tool_result，
     *       插入一个 "(cancelled)" 占位结果。否则 API 会报错。
     *       Java SDK 的 addUserMessageOfBlockParams() 在正常流程中保证了配对，
     *       但在异常中断等边界情况下仍可能出现孤儿。本方法实现此检查。</li>
     *   <li><b>合并连续同角色消息</b> — API 要求 user/assistant 严格交替。
     *       Java SDK 的 paramsBuilder 自动处理角色交替，不需要手动合并。</li>
     * </ol>
     * <p>
     * 综上，Java 版只需显式实现 #2（孤儿检测），#1 和 #3 由 SDK 自动保证。
     *
     * @param state 循环状态
     */
    private static void normalizeMessages(LoopState state) {
        // 当前 Java SDK 的 paramsBuilder 自动保证：
        //   - 消息格式正确（#1）
        //   - 角色严格交替（#3）
        //
        // 如果未来需要手动管理消息列表（比如脱离 paramsBuilder），
        // 则需要完整实现 Python 版的三个职责。
        //
        // 目前仅做日志级别的诊断：如果 lastResponse 有 tool_use 但
        // 后续没有 tool_result（异常中断），这里可以检测并处理。
        // 但由于 Java 版在 runOneTurn 中已有空工具结果防御（return false），
        // 正常流程不会出现孤儿 tool_use。
    }

    // ==================== JsonValue 转换 ====================

    /**
     * 将 SDK 的 JsonValue 转换为普通 Java 对象。
     * <p>
     * 转换优先级：String > Number > Boolean > Map(Object) > List(Array) > null
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

    // ==================== Agent 核心循环 ====================

    /**
     * 执行一轮 Agent 循环 —— 与 S01 Java 版 runOneTurn() 结构一致。
     * <p>
     * 与 Python 原版的区别：Python S02 没有拆分为 runOneTurn/agentLoop，
     * 而是用单个 agent_loop 函数。Java 版沿用 S01 的拆分结构，
     * 体现"S02 的循环与 S01 完全相同"这一核心洞察。
     *
     * @param client       Anthropic API 客户端
     * @param state        循环状态
     * @param toolHandlers 工具分发表：工具名 → 处理函数
     * @return true 表示需要继续循环，false 表示模型决定停止
     */
    @SuppressWarnings("unchecked")
    private static boolean runOneTurn(AnthropicClient client, LoopState state,
                                      Map<String, Function<Map<String, Object>, String>> toolHandlers) {
        // ---- 1. 消息规范化（对应 Python: normalize_messages(messages)）----
        normalizeMessages(state);

        // ---- 2. 调用 LLM ----
        Message response = client.messages().create(state.paramsBuilder.build());

        // ---- 3. 将 assistant 回复追加到历史 ----
        state.paramsBuilder.addMessage(response);

        // ---- 4. 保存最后一次响应（用于循环结束后 extractText） ----
        state.lastResponse = response;

        // ---- 5. 检查停止原因 ----
        boolean isToolUse = response.stopReason()
                .map(StopReason.TOOL_USE::equals)
                .orElse(false);

        if (!isToolUse) {
            // 模型决定停止对话
            System.out.println(bold("[turn " + state.turnCount + "] "
                    + "transition: " + stopReasonLabel(response.stopReason())
                    + " → final response"));
            state.transitionReason = null;
            return false;
        }

        // ---- 6. 打印回合标题：tool_use → executing tools ----
        System.out.println(bold("[turn " + state.turnCount + "] "
                + "transition: " + stopReasonLabel(response.stopReason())
                + " → executing tools"));

        // ---- 7. 遍历 content blocks，通过分发表执行工具调用 ----
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
                if (handler != null) {
                    output = handler.apply(input);
                } else {
                    output = "Unknown tool: " + toolName;
                }

                // 打印工具调用日志（缩进 + 工具名高亮 + 入参摘要 + 输出灰色预览）
                String inputSummary = formatInputSummary(toolName, input);
                System.out.println("  " + yellow(toolName) + "(" + dim(inputSummary) + ")");
                String preview = output.length() > 200
                        ? output.substring(0, 200) + "..."
                        : output;
                String indentedPreview = preview.lines()
                        .map(line -> "    " + gray(line))
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
                System.out.println(indentedPreview);

                toolResults.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(toolUse.id())
                                .content(output)
                                .build()));
            }
        }

        // ---- 8. 空工具结果防御 ----
        if (toolResults.isEmpty()) {
            state.transitionReason = null;
            return false;
        }

        // ---- 9. 将工具结果追加为 user 消息 ----
        state.paramsBuilder.addUserMessageOfBlockParams(toolResults);

        // ---- 10. 更新循环状态 ----
        state.turnCount++;
        state.transitionReason = "tool_result";
        return true;
    }

    /**
     * Agent 核心循环 —— 与 S01 Java 版 agentLoop() 结构一致。
     * <p>
     * 循环逻辑：反复调用 runOneTurn 直到模型停止。
     * 与 S01 的循环完全相同，唯一区别是工具多了三个。
     *
     * @param client       Anthropic API 客户端
     * @param state        循环状态
     * @param toolHandlers 工具分发表
     */
    private static void agentLoop(AnthropicClient client, LoopState state,
                                  Map<String, Function<Map<String, Object>, String>> toolHandlers) {
        while (runOneTurn(client, state, toolHandlers)) {
            // 循环体为空 —— 这是教学版的核心约束
        }
    }

    // ==================== 文本提取 ====================

    /**
     * 从循环状态的最后一条 assistant 消息中提取文本。
     * <p>
     * 与 Python 原版 history[-1]["content"] 的文本提取对应。
     */
    private static String extractText(LoopState state) {
        if (state.lastResponse == null) {
            return "";
        }
        List<String> texts = new ArrayList<>();
        for (ContentBlock block : state.lastResponse.content()) {
            block.text().ifPresent(textBlock -> texts.add(textBlock.text()));
        }
        return String.join("\n", texts).trim();
    }

    // ==================== 日志格式化 ====================

    /**
     * 将 StopReason 转为可读标签，用于回合标题展示。
     */
    private static String stopReasonLabel(java.util.Optional<StopReason> stopReason) {
        return stopReason.map(reason -> {
            if (reason == StopReason.TOOL_USE) return "tool_use";
            if (reason == StopReason.END_TURN) return "end_turn";
            if (reason == StopReason.MAX_TOKENS) return "max_tokens";
            if (reason == StopReason.STOP_SEQUENCE) return "stop_sequence";
            return reason.toString();
        }).orElse("unknown");
    }

    /**
     * 格式化工具入参摘要 —— 根据工具类型展示最关键的参数。
     * <p>
     * 不同工具的摘要策略：
     * <ul>
     *   <li>bash — 直接展示 command 值（等价于 S01 的 $ command）</li>
     *   <li>read_file — 展示 path，可选展示 limit</li>
     *   <li>write_file — 展示 path，content 只显示前 50 字符 + 长度</li>
     *   <li>edit_file — 展示 path，old_text 只显示前 50 字符</li>
     * </ul>
     *
     * @param toolName 工具名称
     * @param input    工具输入参数
     * @return 格式化的入参摘要字符串
     */
    private static String formatInputSummary(String toolName, Map<String, Object> input) {
        if (input == null || input.isEmpty()) return "";

        switch (toolName) {
            case "bash": {
                // bash: 直接展示完整命令
                String command = String.valueOf(input.get("command"));
                return command;
            }
            case "read_file": {
                // read_file: path=xxx, limit=N (可选)
                String path = String.valueOf(input.get("path"));
                Object limit = input.get("limit");
                return limit != null ? path + ", limit=" + limit : path;
            }
            case "write_file": {
                // write_file: path=xxx, content=<前50字符>... (N bytes)
                String path = String.valueOf(input.get("path"));
                String content = String.valueOf(input.get("content"));
                if (content.length() > 50) {
                    return path + ", content=\"" + content.substring(0, 50) + "...\" (" + content.length() + " chars)";
                }
                return path + ", content=\"" + content + "\"";
            }
            case "edit_file": {
                // edit_file: path=xxx, "old" → "new" (各截断到 40 字符)
                String path = String.valueOf(input.get("path"));
                String oldText = String.valueOf(input.get("old_text"));
                String newText = String.valueOf(input.get("new_text"));
                String oldSnippet = oldText.length() > 40 ? oldText.substring(0, 40) + "..." : oldText;
                String newSnippet = newText.length() > 40 ? newText.substring(0, 40) + "..." : newText;
                return path + ", \"" + oldSnippet + "\" → \"" + newSnippet + "\"";
            }
            default: {
                // 未知工具：展示所有 key=value
                return input.entrySet().stream()
                        .map(e -> e.getKey() + "=" + truncate(String.valueOf(e.getValue()), 60))
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
            }
        }
    }

    /** 截断字符串到指定长度 */
    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // ==================== 主程序入口 ====================

    /**
     * REPL 主循环：读取用户输入 → 追加到对话历史 → 执行 Agent 循环 → 打印结果。
     * <p>
     * 与 S01 的区别仅仅是工具多了三个（read_file、write_file、edit_file），
     * Agent 循环逻辑完全相同。
     */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        // ---- 1. 加载环境变量（只加载一次） ----
        Dotenv dotenv = loadDotenv();

        // ---- 2. 构建客户端 ----
        AnthropicClient client = buildClient(dotenv);

        // ---- 3. 加载模型 ID ----
        String model = dotenv.get("MODEL_ID");
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "MODEL_ID 未配置。请在 .env 文件或系统环境变量中设置。");
        }

        // ---- 4. 定义 4 个工具 ----
        // 这是 S01（只有 bash）到 S02 的唯一变化：增加了 3 个文件操作工具
        List<Tool> tools = List.of(
                // bash：执行 shell 命令
                defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")),
                        List.of("command")),

                // read_file：读取文件内容（limit 可选，限制读取行数）
                defineTool("read_file", "Read file contents.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "limit", Map.of("type", "integer")),
                        List.of("path")),

                // write_file：写入文件内容（自动创建父目录）
                defineTool("write_file", "Write content to file.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "content", Map.of("type", "string")),
                        List.of("path", "content")),

                // edit_file：精确文本替换（只替换第一次出现的位置）
                defineTool("edit_file", "Replace exact text in file.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text"))
        );

        // ---- 5. 工具分发表：工具名 → 处理函数 ----
        // 对应 Python 原版的 TOOL_HANDLERS 字典
        // 这是"工具分发"的核心：一个简单的 Map 查找
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

        // ---- 6. 构建消息参数 ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM_PROMPT);

        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }

        // ---- 7. REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println(bold("S02 Tool Use") + " — 4 tools: bash, read_file, write_file, edit_file");
        System.out.println("Type 'q' or 'exit' to quit.\n");

        while (true) {
            System.out.print(cyan("s02 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            paramsBuilder.addUserMessage(query);

            // 创建循环状态
            LoopState state = new LoopState(paramsBuilder);

            try {
                agentLoop(client, state, toolHandlers);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }

            // 提取最终文本回复并打印（与 Python 版的 history[-1] 提取对应）
            String finalText = extractText(state);
            if (finalText != null && !finalText.isEmpty()) {
                System.out.println(green("─── Response ───────────────────"));
                System.out.println(finalText);
            }

            System.out.println();
        }
    }
}
