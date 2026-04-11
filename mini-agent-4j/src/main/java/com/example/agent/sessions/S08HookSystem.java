package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvBuilder;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * S08：Hook 系统 —— 完全自包含实现（不依赖 core/、tools/、util/ 包）。
 * <p>
 * Hook 是主循环的扩展点。它们让使用者在不改写循环本身的情况下注入行为。
 * <p>
 * 教学版支持三种 Hook 事件：
 * - SessionStart：会话启动时触发
 * - PreToolUse：工具执行前触发（可拦截）
 * - PostToolUse：工具执行后触发（可追加信息）
 * <p>
 * 退出码契约：
 * - 0 → 继续（stdout 可包含 JSON：updatedInput、additionalContext、permissionDecision）
 * - 1 → 阻断执行（stderr 包含原因）
 * - 2 → 注入消息（stderr 包含要注入的文本）
 * <p>
 * 关键洞察："不改循环，扩展 Agent。"
 * <p>
 * 本文件将所有基础设施内联：
 * - buildClient()：构建 Anthropic API 客户端
 * - loadModel()：从环境变量加载模型 ID
 * - defineTool()：构建 SDK Tool 定义
 * - runBash()：执行 shell 命令（OS 自适应、危险命令拦截、超时、输出截断）
 * - runRead()：读取文件内容（行数限制、路径沙箱、输出截断）
 * - runWrite()：写入文件（自动创建目录、路径沙箱）
 * - runEdit()：精确文本替换（单次替换、Pattern.quote 字面量匹配）
 * - safePath()：路径沙箱校验，防止路径穿越
 * - HookManager：内部类，加载并执行 Hook 定义
 * - ANSI 输出：终端彩色文本
 * - agentLoop()：核心 LLM 调用 → Hook 拦截 → 工具执行 → Hook 回调 → 结果回传循环
 * - jsonValueToObject()：JsonValue → 普通 Java 对象转换
 * <p>
 * 对应 Python 原版：s08_hook_system.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s08_hook_system.py">Python 原版</a>
 */
public class S08HookSystem {

    // ==================== 常量 ====================

    /** 最大输出长度（字符），与 Python 原版 50000 对齐 */
    private static final int MAX_OUTPUT = 50000;

    /** bash 命令超时（秒），与 Python 原版 120s 对齐 */
    private static final int BASH_TIMEOUT = 120;

    /** Hook 子进程超时（秒），与 Python 原版 30s 对齐 */
    private static final int HOOK_TIMEOUT = 30;

    /** 环境变量内容最大长度，防止传递超长字符串给子进程 */
    private static final int HOOK_ENV_MAX_LENGTH = 10000;

    /** 危险命令黑名单，防止模型执行破坏性操作 */
    private static final List<String> DANGEROUS_COMMANDS = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    /** 工作目录（Agent 的文件操作沙箱根目录） */
    private static final Path WORK_DIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /** 工作区信任标记文件路径，Hook 只在受信任的工作区中运行 */
    private static final Path TRUST_MARKER = WORK_DIR.resolve(".claude").resolve(".claude_trusted");

    /** 系统提示词：告诉模型它是一个编码 Agent，需要用工具完成任务 */
    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + WORK_DIR + ". Use tools to solve tasks.";

    // ==================== ANSI 颜色输出 ====================
    // 终端彩色文本，让 REPL 和工具日志更易读

    private static final String ANSI_RESET  = "\033[0m";
    private static final String ANSI_BOLD   = "\033[1m";
    private static final String ANSI_DIM    = "\033[2m";
    private static final String ANSI_CYAN   = "\033[36m";
    private static final String ANSI_RED    = "\033[31m";
    private static final String ANSI_YELLOW = "\033[33m";

    /** 检测终端是否支持 ANSI 转义码 */
    private static final boolean ANSI_SUPPORTED = detectAnsi();

    private static boolean detectAnsi() {
        // Unix 终端通常通过 TERM 环境变量标识
        String term = System.getenv("TERM");
        if (term != null && !term.isEmpty()) return true;
        // Windows Terminal 会设置 WT_SESSION
        if (System.getenv("WT_SESSION") != null) return true;
        // ConEmu 终端
        if ("ON".equalsIgnoreCase(System.getenv("ConEmuANSI"))) return true;
        // 现代 Windows 10+ 终端通常也支持
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
                .ignoreIfMissing()    // .env 不存在时不报错
                .systemProperties()   // 同时读取系统属性
                .load();
    }

    /**
     * 构建 Anthropic API 客户端。
     * <p>
     * 支持自定义 baseUrl（用于第三方 API 兼容端点，如 OpenRouter）。
     * 如果设置了 ANTHROPIC_BASE_URL，则清除 ANTHROPIC_AUTH_TOKEN 避免冲突
     * （与 Python 原版行为对齐）。
     */
    private static AnthropicClient buildClient() {
        Dotenv dotenv = loadDotenv();

        // 如果设置了自定义 baseUrl，清除 auth token 避免冲突
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
        // 使用默认 Anthropic 端点
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
     * <p>
     * 示例：
     * <pre>
     * defineTool("bash", "Run a shell command.",
     *     Map.of("command", Map.of("type", "string")),
     *     List.of("command"));
     * </pre>
     *
     * @param name        工具名称（模型调用时使用）
     * @param description 工具描述（告诉模型工具的用途）
     * @param properties  JSON Schema 属性定义（Map<属性名, Map<类型, ...>>）
     * @param required    必需属性列表，null 或空列表表示无必需属性
     * @return 构建好的 Tool 对象
     */
    private static Tool defineTool(String name, String description,
                                   Map<String, Object> properties,
                                   List<String> required) {
        var schemaBuilder = Tool.InputSchema.builder()
                .properties(JsonValue.from(properties));

        // 只有非空时才添加 required 字段
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
     * 处理流程：
     * 1. 将相对路径拼接到工作目录
     * 2. 规范化（消除 .. 和 .）
     * 3. 检查规范化后的路径是否仍在工作目录下
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
     *
     * @param command 要执行的 shell 命令
     * @return 命令输出（stdout + stderr 合并）
     */
    private static String runBash(String command) {
        // 危险命令拦截
        for (String dangerous : DANGEROUS_COMMANDS) {
            if (command.contains(dangerous)) {
                return "Error: Dangerous command blocked";
            }
        }

        try {
            // OS 自适应：选择正确的 shell
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            pb.directory(WORK_DIR.toFile());
            pb.redirectErrorStream(true); // 合并 stdout 和 stderr

            Process process = pb.start();

            // 读取输出，边读边检查长度
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append("\n");
                    }
                    output.append(line);
                    // 提前截断，避免内存溢出
                    if (output.length() > MAX_OUTPUT) {
                        break;
                    }
                }
            }

            // 等待进程结束，带超时
            boolean finished = process.waitFor(BASH_TIMEOUT, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (" + BASH_TIMEOUT + "s)";
            }

            String result = output.toString().trim();
            if (result.isEmpty()) {
                return "(no output)";
            }
            // 最终截断保护
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
     * 安全特性：
     * - 路径沙箱校验（防止路径穿越）
     * - 可选行数限制（limit 参数）
     * - 输出截断到 50000 字符
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

            // 应用行数限制
            if (limit != null && limit > 0 && limit < lines.size()) {
                int totalLines = lines.size();
                lines = new ArrayList<>(lines.subList(0, limit));
                lines.add("... (" + (totalLines - limit) + " more lines)");
            }

            String result = String.join("\n", lines);
            // 截断过长输出
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
     * 安全特性：
     * - 路径沙箱校验（防止路径穿越）
     * - 自动创建父目录（对应 Python 的 fp.parent.mkdir(parents=True, exist_ok=True)）
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
            // 自动创建父目录（像 Python 的 mkdir -p 一样）
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
     * 精确文本替换（仅替换第一次出现）。
     * <p>
     * 使用 Pattern.quote() 确保 old_text 作为字面量匹配，
     * 不会被当作正则表达式解析（比如替换包含 $ 或 . 的文本时）。
     * 使用 Matcher.quoteReplacement() 确保 new_text 中的 \ 和 $ 也被正确处理。
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

            // 先做快速检查，避免不必要的正则编译
            if (!content.contains(oldText)) {
                return "Error: Text not found in " + path;
            }

            // 使用 Pattern.quote 确保字面量匹配（不解释正则元字符）
            // 使用 Matcher.quoteReplacement 确保替换文本中的特殊字符不被解释
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

    // ==================== Hook 系统 ====================

    /**
     * Hook 管理器 —— 加载并执行 Hook 定义。
     * <p>
     * 内部类，负责三件事：
     * 1. 从 .hooks.json 加载 Hook 定义
     * 2. 根据事件类型和 matcher 筛选匹配的 Hook
     * 3. 执行 Hook 子进程，解析退出码和输出
     * <p>
     * 对应 Python 原版：HookManager 类。
     */
    static class HookManager {

        /** Hook 事件类型常量 */
        private static final String EVENT_PRE_TOOL_USE  = "PreToolUse";
        private static final String EVENT_POST_TOOL_USE = "PostToolUse";
        private static final String EVENT_SESSION_START = "SessionStart";

        /** 支持的事件类型列表 */
        private static final List<String> HOOK_EVENTS = List.of(
                EVENT_PRE_TOOL_USE, EVENT_POST_TOOL_USE, EVENT_SESSION_START
        );

        /**
         * Hook 定义结构。
         * <p>
         * 每条 Hook 包含：
         * - matcher：工具名过滤器（"*" 匹配所有，或指定工具名）
         * - command：要执行的 shell 命令
         */
        static class HookDefinition {
            final String matcher;
            final String command;

            HookDefinition(String matcher, String command) {
                this.matcher = matcher;
                this.command = command;
            }
        }

        /**
         * Hook 执行结果。
         * <p>
         * 返回给调用方的聚合结果：
         * - blocked：是否有任何 Hook 返回退出码 1（阻断）
         * - blockReason：阻断原因（来自 stderr）
         * - messages：要注入的消息列表（来自退出码 2 的 stderr）
         * - permissionOverride：权限决策覆盖（来自退出码 0 的 JSON 输出）
         */
        static class HookResult {
            boolean blocked = false;
            String blockReason = null;
            List<String> messages = new ArrayList<>();
            String permissionOverride = null;
        }

        /** Hook 注册表：事件类型 → Hook 定义列表 */
        private final Map<String, List<HookDefinition>> hooks;

        /** SDK 模式标志：在 SDK 模式下跳过工作区信任检查 */
        private final boolean sdkMode;

        /**
         * 构造 Hook 管理器。
         * <p>
         * 从 .hooks.json 加载 Hook 定义（如果文件存在）。
         * JSON 格式：
         * <pre>
         * {
         *   "hooks": {
         *     "PreToolUse": [
         *       {"matcher": "bash", "command": "python check.py"}
         *     ],
         *     "PostToolUse": [...],
         *     "SessionStart": [...]
         *   }
         * }
         * </pre>
         *
         * @param configPath Hook 配置文件路径，null 则使用默认路径（工作目录下的 .hooks.json）
         * @param sdkMode    是否为 SDK 模式（跳过工作区信任检查）
         */
        HookManager(Path configPath, boolean sdkMode) {
            this.sdkMode = sdkMode;

            // 初始化空 Hook 列表
            this.hooks = new LinkedHashMap<>();
            for (String event : HOOK_EVENTS) {
                hooks.put(event, new ArrayList<>());
            }

            // 从配置文件加载 Hook
            Path path = configPath != null ? configPath : WORK_DIR.resolve(".hooks.json");
            if (Files.exists(path)) {
                try {
                    String json = Files.readString(path);
                    Map<String, Object> config = parseSimpleJson(json);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> hooksConfig = (Map<String, Object>) config.get("hooks");
                    if (hooksConfig != null) {
                        for (String event : HOOK_EVENTS) {
                            @SuppressWarnings("unchecked")
                            List<Object> hookList = (List<Object>) hooksConfig.get(event);
                            if (hookList != null) {
                                for (Object item : hookList) {
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> hookDef = (Map<String, Object>) item;
                                    String matcher = (String) hookDef.getOrDefault("matcher", "*");
                                    String command = (String) hookDef.getOrDefault("command", "");
                                    if (!command.isEmpty()) {
                                        hooks.get(event).add(new HookDefinition(matcher, command));
                                    }
                                }
                            }
                        }
                    }
                    System.out.println(dim("[Hooks loaded from " + path + "]"));
                } catch (Exception e) {
                    System.out.println(yellow("[Hook config error: " + e.getMessage() + "]"));
                }
            }
        }

        /**
         * 检查工作区是否受信任。
         * <p>
         * Hook 只在受信任的工作区中运行。
         * 教学版使用简单的标记文件（.claude/.claude_trusted）。
         * SDK 模式下视为隐式信任。
         *
         * @return true 表示工作区受信任
         */
        private boolean checkWorkspaceTrust() {
            if (sdkMode) return true;
            return Files.exists(TRUST_MARKER);
        }

        /**
         * 执行指定事件的所有 Hook。
         * <p>
         * 处理流程：
         * 1. 检查工作区信任
         * 2. 遍历匹配的 Hook（根据 matcher 过滤）
         * 3. 为每个 Hook 构建环境变量
         * 4. 执行子进程（30 秒超时）
         * 5. 根据退出码解析结果
         * <p>
         * 环境变量：
         * - HOOK_EVENT：事件类型
         * - HOOK_TOOL_NAME：工具名称
         * - HOOK_TOOL_INPUT：工具输入（JSON 字符串，截断到 10000 字符）
         * - HOOK_TOOL_OUTPUT：工具输出（仅 PostToolUse 事件，截断到 10000 字符）
         * <p>
         * 退出码契约：
         * - 0：继续（stdout 可包含 JSON）
         * - 1：阻断（stderr 包含原因）
         * - 2：注入消息（stderr 包含要注入的文本）
         *
         * @param event   Hook 事件类型（PreToolUse / PostToolUse / SessionStart）
         * @param context Hook 上下文，包含 tool_name、tool_input、tool_output 等
         * @return Hook 执行聚合结果
         */
        HookResult runHooks(String event, Map<String, Object> context) {
            HookResult result = new HookResult();

            // 信任门控：不受信任的工作区不执行 Hook
            if (!checkWorkspaceTrust()) {
                return result;
            }

            List<HookDefinition> eventHooks = hooks.getOrDefault(event, List.of());

            for (HookDefinition hookDef : eventHooks) {
                // Matcher 过滤：检查当前工具是否匹配此 Hook
                String matcher = hookDef.matcher;
                if (matcher != null && context != null) {
                    String toolName = (String) context.getOrDefault("tool_name", "");
                    // "*" 匹配所有工具，否则精确匹配工具名
                    if (!"*".equals(matcher) && !matcher.equals(toolName)) {
                        continue;
                    }
                }

                String command = hookDef.command;
                if (command == null || command.isEmpty()) continue;

                // ---- 构建环境变量 ----
                ProcessBuilder pb;
                if (System.getProperty("os.name").toLowerCase().contains("win")) {
                    pb = new ProcessBuilder("cmd", "/c", command);
                } else {
                    pb = new ProcessBuilder("bash", "-c", command);
                }
                pb.directory(WORK_DIR.toFile());
                pb.redirectErrorStream(false); // stdout 和 stderr 分开捕获

                // 设置 Hook 专用环境变量
                Map<String, String> env = pb.environment();
                if (context != null) {
                    env.put("HOOK_EVENT", event != null ? event : "");
                    env.put("HOOK_TOOL_NAME", (String) context.getOrDefault("tool_name", ""));

                    // HOOK_TOOL_INPUT：工具输入的 JSON 字符串（截断保护）
                    Object toolInput = context.get("tool_input");
                    String inputJson = toolInput != null ? toJsonString(toolInput) : "{}";
                    if (inputJson.length() > HOOK_ENV_MAX_LENGTH) {
                        inputJson = inputJson.substring(0, HOOK_ENV_MAX_LENGTH);
                    }
                    env.put("HOOK_TOOL_INPUT", inputJson);

                    // HOOK_TOOL_OUTPUT：工具输出（仅 PostToolUse 事件）
                    Object toolOutput = context.get("tool_output");
                    if (toolOutput != null) {
                        String outputStr = String.valueOf(toolOutput);
                        if (outputStr.length() > HOOK_ENV_MAX_LENGTH) {
                            outputStr = outputStr.substring(0, HOOK_ENV_MAX_LENGTH);
                        }
                        env.put("HOOK_TOOL_OUTPUT", outputStr);
                    }
                }

                // ---- 执行子进程 ----
                try {
                    Process process = pb.start();

                    // 读取 stdout
                    StringBuilder stdout = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (stdout.length() > 0) stdout.append("\n");
                            stdout.append(line);
                        }
                    }

                    // 读取 stderr
                    StringBuilder stderr = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getErrorStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (stderr.length() > 0) stderr.append("\n");
                            stderr.append(line);
                        }
                    }

                    // 等待进程结束，带超时
                    boolean finished = process.waitFor(HOOK_TIMEOUT, TimeUnit.SECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        System.out.println(yellow("  [hook:" + event + "] Timeout (" + HOOK_TIMEOUT + "s)"));
                        continue;
                    }

                    int exitCode = process.exitValue();
                    String stdoutStr = stdout.toString().trim();
                    String stderrStr = stderr.toString().trim();

                    if (exitCode == 0) {
                        // ---- 退出码 0：继续 ----
                        if (!stdoutStr.isEmpty()) {
                            System.out.println(dim("  [hook:" + event + "] " +
                                    stdoutStr.substring(0, Math.min(stdoutStr.length(), 100))));
                        }

                        // 尝试解析 stdout 为 JSON（可选结构化输出）
                        // 支持：updatedInput、additionalContext、permissionDecision
                        if (!stdoutStr.isEmpty()) {
                            try {
                                Map<String, Object> hookOutput = parseSimpleJson(stdoutStr);

                                // updatedInput：更新工具输入参数
                                Object updatedInput = hookOutput.get("updatedInput");
                                if (updatedInput != null && context != null) {
                                    context.put("tool_input", updatedInput);
                                }

                                // additionalContext：追加额外上下文信息
                                Object additionalCtx = hookOutput.get("additionalContext");
                                if (additionalCtx != null) {
                                    result.messages.add(String.valueOf(additionalCtx));
                                }

                                // permissionDecision：覆盖权限决策
                                Object permDecision = hookOutput.get("permissionDecision");
                                if (permDecision != null) {
                                    result.permissionOverride = String.valueOf(permDecision);
                                }
                            } catch (Exception ignored) {
                                // stdout 不是 JSON —— 对于简单 Hook 来说是正常的
                            }
                        }

                    } else if (exitCode == 1) {
                        // ---- 退出码 1：阻断执行 ----
                        result.blocked = true;
                        result.blockReason = stderrStr.isEmpty() ? "Blocked by hook" : stderrStr;
                        System.out.println(red("  [hook:" + event + "] BLOCKED: " +
                                result.blockReason.substring(0, Math.min(result.blockReason.length(), 200))));

                    } else if (exitCode == 2) {
                        // ---- 退出码 2：注入消息 ----
                        if (!stderrStr.isEmpty()) {
                            result.messages.add(stderrStr);
                            System.out.println(yellow("  [hook:" + event + "] INJECT: " +
                                    stderrStr.substring(0, Math.min(stderrStr.length(), 200))));
                        }

                    }
                    // 其他退出码：忽略

                } catch (Exception e) {
                    System.out.println(yellow("  [hook:" + event + "] Error: " + e.getMessage()));
                }
            }

            return result;
        }

        // ---- 简易 JSON 解析（不依赖外部库） ----

        /**
         * 将简单 Java 对象序列化为 JSON 字符串。
         * <p>
         * 支持 Map、List、String、Number、Boolean 类型。
         * 这是一个最小实现，不处理特殊字符转义等边缘情况，
         * 对教学用途足够。
         *
         * @param obj 要序列化的对象
         * @return JSON 字符串
         */
        private String toJsonString(Object obj) {
            if (obj == null) return "null";
            if (obj instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
            if (obj instanceof Number || obj instanceof Boolean) return String.valueOf(obj);
            if (obj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) obj;
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (var entry : map.entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\"").append(entry.getKey()).append("\":").append(toJsonString(entry.getValue()));
                    first = false;
                }
                sb.append("}");
                return sb.toString();
            }
            if (obj instanceof List) {
                List<?> list = (List<?>) obj;
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object item : list) {
                    if (!first) sb.append(",");
                    sb.append(toJsonString(item));
                    first = false;
                }
                sb.append("]");
                return sb.toString();
            }
            return "\"" + obj.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }

    // ==================== 简易 JSON 解析器 ====================

    /**
     * 解析简单的 JSON 字符串为 Java Map。
     * <p>
     * 这是一个最小实现，用于解析 .hooks.json 配置文件和 Hook 的 stdout 输出。
     * 不支持嵌套对象中的复杂转义，但对教学用途足够。
     * <p>
     * 支持的类型：
     * - 字符串（双引号）
     * - 数字（整数和浮点数）
     * - 布尔值（true/false）
     * - null
     * - 对象（Map）
     * - 数组（List）
     *
     * @param json JSON 字符串
     * @return 解析后的 Map
     */
    private static Map<String, Object> parseSimpleJson(String json) {
        return new SimpleJsonParser(json).parseObject();
    }

    /**
     * 简易 JSON 解析器内部实现。
     * <p>
     * 使用游标式解析：维护一个位置指针，逐步前进消费字符。
     * 支持 JSON 的所有基本类型。
     */
    private static class SimpleJsonParser {
        private final String input;
        private int pos;

        SimpleJsonParser(String input) {
            this.input = input.trim();
            this.pos = 0;
        }

        /** 解析 JSON 对象为 Map */
        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) != '{') return map;
            pos++; // 跳过 '{'
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '}') { pos++; return map; }

            while (pos < input.length()) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == ',') { pos++; continue; }
                if (pos < input.length() && input.charAt(pos) == '}') { pos++; break; }
                break;
            }
            return map;
        }

        /** 解析 JSON 值（根据首字符判断类型） */
        private Object parseValue() {
            skipWhitespace();
            if (pos >= input.length()) return null;
            char c = input.charAt(pos);
            if (c == '"') return parseString();
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') { pos += 4; return null; } // null
            if (c == '-' || Character.isDigit(c)) return parseNumber();
            return null;
        }

        /** 解析 JSON 字符串（处理转义字符） */
        private String parseString() {
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) != '"') return "";
            pos++; // 跳过开头 '"'
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') break;
                if (c == '\\' && pos < input.length()) {
                    char next = input.charAt(pos++);
                    switch (next) {
                        case '"':  sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/'); break;
                        case 'n':  sb.append('\n'); break;
                        case 't':  sb.append('\t'); break;
                        case 'r':  sb.append('\r'); break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'u':
                            // Unicode escape: consume next 4 hex chars
                            if (pos + 4 <= input.length()) {
                                String hex = input.substring(pos, pos + 4);
                                try {
                                    sb.append((char) Integer.parseInt(hex, 16));
                                    pos += 4;
                                } catch (NumberFormatException e) {
                                    // 无效的十六进制，原样追加
                                    sb.append("\\u").append(hex);
                                    pos += 4;
                                }
                            }
                            break;
                        default:   sb.append(next); break;
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        /** 解析 JSON 数组 */
        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // 跳过 '['
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ']') { pos++; return list; }
            while (pos < input.length()) {
                list.add(parseValue());
                skipWhitespace();
                if (pos < input.length() && input.charAt(pos) == ',') { pos++; continue; }
                if (pos < input.length() && input.charAt(pos) == ']') { pos++; break; }
                break;
            }
            return list;
        }

        /** 解析 JSON 数字（整数优先，有浮点则返回 Double） */
        private Number parseNumber() {
            int start = pos;
            if (pos < input.length() && input.charAt(pos) == '-') pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            boolean isFloat = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            String numStr = input.substring(start, pos);
            return isFloat ? Double.parseDouble(numStr) : Long.parseLong(numStr);
        }

        /** 解析 JSON 布尔值 */
        private boolean parseBoolean() {
            if (input.startsWith("true", pos)) { pos += 4; return true; }
            if (input.startsWith("false", pos)) { pos += 5; return false; }
            return false;
        }

        /** 跳过空白字符 */
        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) pos++;
        }

        /** 断言期望的字符 */
        private void expect(char c) {
            if (pos < input.length() && input.charAt(pos) == c) pos++;
        }
    }

    // ==================== JsonValue 转换 ====================

    /**
     * 将 SDK 的 JsonValue 转换为普通 Java 对象。
     * <p>
     * Anthropic SDK 返回的工具输入是 JsonValue 类型，
     * 我们需要递归地将其转换为 Map/List/String/Number/Boolean 等 Java 原生类型，
     * 以便在分发表中统一处理。
     * <p>
     * 转换优先级：String > Number > Boolean > Map(Object) > List(Array) > null
     *
     * @param value JsonValue 实例
     * @return 对应的 Java 原生对象
     */
    @SuppressWarnings("unchecked")
    private static Object jsonValueToObject(JsonValue value) {
        if (value == null) return null;

        // 字符串（最常见的类型，优先检查）
        var strOpt = value.asString();
        if (strOpt.isPresent()) return strOpt.get();

        // 数字
        var numOpt = value.asNumber();
        if (numOpt.isPresent()) return numOpt.get();

        // 布尔值
        var boolOpt = value.asBoolean();
        if (boolOpt.isPresent()) return boolOpt.get();

        // 对象（Map）—— 递归转换每个值
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
            // 类型不匹配，继续尝试下一种
        }

        // 数组（List）—— 递归转换每个元素
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
            // 类型不匹配
        }

        return null;
    }

    // ==================== Agent 核心循环（Hook 感知版） ====================

    /**
     * Hook 感知的 Agent 核心循环：LLM 调用 → PreToolUse Hook → 工具执行 → PostToolUse Hook → 结果回传。
     * <p>
     * 这是 S08 的核心变更：在工具执行前后插入 Hook 调用。
     * <p>
     * 循环模式（与 S02 相同的基础循环，增加了 Hook 包装）：
     * <pre>
     *   while (stopReason == TOOL_USE) {
     *       response = LLM(messages, tools);
     *       for each tool_use block:
     *           pre = hooks.runHooks("PreToolUse", ctx);
     *           if (pre.blocked) → skip, report blocked
     *           output = execute tool;
     *           post = hooks.runHooks("PostToolUse", ctx);
     *           output += post.messages;
     *           append tool_result;
     *   }
     * </pre>
     * <p>
     * 对应 Python 原版：agent_loop(messages, hooks) 函数。
     *
     * @param client       Anthropic API 客户端
     * @param model        模型 ID
     * @param paramsBuilder 消息创建参数构建器（包含已有对话历史）
     * @param tools        工具定义列表（发送给 LLM）
     * @param toolHandlers 工具分发表：工具名 → 处理函数
     * @param hookManager  Hook 管理器实例
     */
    @SuppressWarnings("unchecked")
    private static void agentLoop(AnthropicClient client, String model,
                                  MessageCreateParams.Builder paramsBuilder,
                                  List<Tool> tools,
                                  Map<String, Function<Map<String, Object>, String>> toolHandlers,
                                  HookManager hookManager) {
        while (true) {
            // ---- 1. 调用 LLM ----
            Message response = client.messages().create(paramsBuilder.build());

            // ---- 2. 将 assistant 回复追加到历史 ----
            paramsBuilder.addMessage(response);

            // ---- 3. 检查是否需要继续执行工具 ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                // 模型决定停止，打印文本回复给用户
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(textBlock ->
                            System.out.println(textBlock.text()));
                }
                return; // 跳出循环，回到 REPL 等待下一个用户输入
            }

            // ---- 4. 遍历 content blocks，执行工具调用（带 Hook 包装） ----
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (!block.isToolUse()) continue;

                ToolUseBlock toolUse = block.asToolUse();
                String toolName = toolUse.name();

                // 从 JsonValue 提取输入参数（转换为 Map<String, Object>）
                Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());
                if (input == null) input = new LinkedHashMap<>();

                // 构建 Hook 上下文
                Map<String, Object> hookContext = new LinkedHashMap<>();
                hookContext.put("tool_name", toolName);
                hookContext.put("tool_input", input);

                // ---- PreToolUse Hook ----
                HookManager.HookResult preResult = hookManager.runHooks("PreToolUse", hookContext);

                // 注入 PreToolUse Hook 消息到结果
                for (String msg : preResult.messages) {
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content("[Hook message]: " + msg)
                                    .build()));
                }

                // 如果被 Hook 阻断，跳过工具执行
                if (preResult.blocked) {
                    String reason = preResult.blockReason != null ? preResult.blockReason : "Blocked by hook";
                    String output = "Tool blocked by PreToolUse hook: " + reason;
                    System.out.println(red("  BLOCKED: " + reason.substring(0, Math.min(reason.length(), 200))));
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                    continue;
                }

                // ---- 执行工具 ----
                Function<Map<String, Object>, String> handler = toolHandlers.get(toolName);
                String output;
                try {
                    // 如果 Hook 修改了 tool_input，使用更新后的值
                    @SuppressWarnings("unchecked")
                    Map<String, Object> effectiveInput = (Map<String, Object>) hookContext.getOrDefault("tool_input", input);
                    output = handler != null ? handler.apply(effectiveInput) : "Unknown tool: " + toolName;
                } catch (Exception e) {
                    output = "Error: " + e.getMessage();
                }
                System.out.println(bold("> " + toolName) + ":");
                System.out.println(dim("  " + output.substring(0, Math.min(output.length(), 200))));

                // ---- PostToolUse Hook ----
                hookContext.put("tool_output", output);
                HookManager.HookResult postResult = hookManager.runHooks("PostToolUse", hookContext);

                // 追加 PostToolUse Hook 消息到输出
                for (String msg : postResult.messages) {
                    output += "\n[Hook note]: " + msg;
                }

                // 构造 tool_result 消息块，回传给 LLM
                toolResults.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(toolUse.id())
                                .content(output)
                                .build()));
            }

            // ---- 5. 将工具结果追加为 user 消息 ----
            // API 要求 tool_result 必须以 user 角色发送
            paramsBuilder.addUserMessageOfBlockParams(toolResults);
        }
    }

    // ==================== 主程序入口 ====================

    /**
     * REPL 主循环：读取用户输入 → 追加到对话历史 → 执行 Agent 循环 → 打印结果。
     * <p>
     * 整体流程：
     * <pre>
     * hooks = HookManager()
     * hooks.runHooks("SessionStart")
     * while True:
     *     query = input("s08 >> ")     # 读取用户输入
     *     messages.append(query)       # 追加到历史
     *     agent_loop(messages, hooks)  # 执行 Hook 感知的 Agent 循环
     * </pre>
     * <p>
     * 与 S02 的区别：
     * - 增加了 HookManager 初始化
     * - 启动时触发 SessionStart Hook
     * - Agent 循环在每个工具调用前后包装了 Hook
     */
    public static void main(String[] args) {
        // ---- 构建客户端和加载模型 ----
        AnthropicClient client = buildClient();
        String model = loadModel();

        // ---- 初始化 Hook 管理器 ----
        // 从工作目录下的 .hooks.json 加载 Hook 定义
        // 传入 null 表示使用默认路径（WORK_DIR/.hooks.json）
        HookManager hookManager = new HookManager(null, false);

        // ---- 触发 SessionStart Hook ----
        // 会话启动时触发，用于执行初始化逻辑
        Map<String, Object> sessionContext = new LinkedHashMap<>();
        sessionContext.put("tool_name", "");
        sessionContext.put("tool_input", new LinkedHashMap<>());
        hookManager.runHooks("SessionStart", sessionContext);

        // ---- 定义 4 个工具 ----
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

        // ---- 工具分发表：工具名 → 处理函数 ----
        // 对应 Python 原版的 TOOL_HANDLERS 字典
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

        // ---- 构建消息参数（包含系统提示词、模型、工具、maxTokens） ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(SYSTEM_PROMPT);

        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }

        // ---- REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println(bold("S08 Hook System") + " — 4 tools with PreToolUse/PostToolUse/SessionStart hooks");
        System.out.println("Type 'q' or 'exit' to quit.\n");

        while (true) {
            // 打印提示符（青色 "s08 >>"）
            System.out.print(cyan("s08 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();
            // 空输入或退出命令 → 结束
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // 追加用户消息到对话历史
            paramsBuilder.addUserMessage(query);

            // 执行 Hook 感知的 Agent 循环
            try {
                agentLoop(client, model, paramsBuilder, tools, toolHandlers, hookManager);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }
            System.out.println(); // 每轮结束后空一行，视觉分隔
        }

        System.out.println(dim("Bye!"));
    }
}
