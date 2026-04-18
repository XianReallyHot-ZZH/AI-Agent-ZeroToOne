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
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * S04：Subagent —— 上下文隔离保护模型的思路清晰度。
 * <p>
 * 完全自包含实现，不依赖 core/、tools/、util/ 包。
 * 所有基础设施（客户端构建、工具定义、工具实现、Agent 循环）全部内联。
 * <p>
 * 核心思想：
 * 生成一个拥有全新 messages=[] 的子 Agent。子 Agent 共享文件系统但不共享对话历史，
 * 完成后仅向父 Agent 返回一段摘要。这样父 Agent 的上下文始终保持干净。
 * <p>
 * 对应 Python 原版的架构图：
 * <pre>
 *     Parent agent                     Subagent
 *     +------------------+             +------------------+
 *     | messages=[...]   |             | messages=[]      |  <-- 全新上下文
 *     |                  |  dispatch   |                  |
 *     | tool: task       | ---------->| while tool_use:  |
 *     |   prompt="..."   |            |   call tools     |
 *     |   description="" |            |   append results |
 *     |                  |  summary   |                  |
 *     |   result = "..." | <--------- | return last text |
 *     +------------------+             +------------------+
 * </pre>
 * <p>
 * 关键洞察："Fresh messages=[] gives context isolation. The parent stays clean."
 * <p>
 * 与真实 Claude Code 的对比：
 * - 本实现：进程内隔离（fresh messages=[]），4 个子工具
 * - 真实 Claude Code：5 种后端（in-process, tmux, iTerm2, fork, remote），
 *   ~20 个隔离字段（tools, permissions, cwd, env, hooks 等），
 *   使用 .claude/agents/*.md 的 YAML frontmatter 定义 Agent
 * <p>
 * 对应 Python 原版：s04_subagent.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s04_subagent.py">Python 原版</a>
 */
public class S04Subagent {

    // ==================== 常量 ====================

    /** 最大输出长度（字符），与 Python 原版 50000 对齐 */
    private static final int MAX_OUTPUT = 50000;

    /** bash 命令超时（秒），与 Python 原版 120s 对齐 */
    private static final int BASH_TIMEOUT = 120;

    /** 子 Agent 最大循环轮次，防止无限递归 */
    private static final int SUBAGENT_MAX_ROUNDS = 30;

    /** 危险命令黑名单，防止模型执行破坏性操作 */
    private static final List<String> DANGEROUS_COMMANDS = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    /** 工作目录（Agent 的文件操作沙箱根目录） */
    private static final Path WORK_DIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /** 父 Agent 系统提示词：告诉它可以用 task 工具委派子任务 */
    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + WORK_DIR + ". Use the task tool to delegate exploration or subtasks.";

    /** 子 Agent 系统提示词：告诉它完成给定任务后要总结 */
    private static final String SUBAGENT_SYSTEM =
            "You are a coding subagent at " + WORK_DIR + ". Complete the given task, then summarize your findings.";

    // ==================== ANSI 颜色输出 ====================

    private static final String ANSI_RESET  = "\033[0m";
    private static final String ANSI_BOLD   = "\033[1m";
    private static final String ANSI_DIM    = "\033[2m";
    private static final String ANSI_CYAN   = "\033[36m";
    private static final String ANSI_RED    = "\033[31m";

    /** 检测终端是否支持 ANSI 转义码 */
    private static final boolean ANSI_SUPPORTED = detectAnsi();

    private static boolean detectAnsi() {
        String term = System.getenv("TERM");
        if (term != null && !term.isEmpty()) return true;
        if (System.getenv("WT_SESSION") != null) return true;
        if ("ON".equalsIgnoreCase(System.getenv("ConEmuANSI"))) return true;
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String ansi(String code, String text) {
        return ANSI_SUPPORTED ? code + text + ANSI_RESET : text;
    }

    private static String bold(String text) { return ansi(ANSI_BOLD, text); }
    private static String dim(String text)  { return ansi(ANSI_DIM, text); }
    private static String cyan(String text) { return ansi(ANSI_CYAN, text); }
    private static String red(String text)  { return ansi(ANSI_RED, text); }

    // ==================== Agent 定义文件解析器 ====================

    /**
     * Agent 定义文件解析器（对应 Python 原版的 AgentTemplate 类）。
     * <p>
     * 真实 Claude Code 从 .claude/agents/*.md 加载 Agent 定义，
     * 支持 YAML frontmatter 定义 Agent 的各种配置。
     * <p>
     * Frontmatter 支持的字段示例：
     * <pre>
     * ---
     * name: code-reviewer
     * tools: bash, read_file, edit_file
     * disallowedTools: write_file
     * model: claude-sonnet-4-20250514
     * maxTurns: 20
     * ---
     * You are a code reviewer agent...
     * </pre>
     * <p>
     * Frontmatter 字段说明（对应真实 Claude Code）：
     * name, tools, disallowedTools, skills, hooks, model, effort,
     * permissionMode, maxTurns, memory, isolation, color,
     * background, initialPrompt, mcpServers。
     * <p>
     * 3 种来源：内置 Agent、自定义（.claude/agents/）、插件提供。
     * <p>
     * 对应 Python 原版：AgentTemplate 类。
     */
    static class AgentTemplate {
        /** 定义文件路径 */
        final Path path;
        /** Agent 名称（优先取 frontmatter 中的 name，否则取文件名） */
        String name;
        /** Frontmatter 中解析出的配置键值对 */
        final Map<String, String> config = new LinkedHashMap<>();
        /** Agent 系统提示词（frontmatter 之后的正文部分） */
        String systemPrompt = "";

        /**
         * 从文件路径构造并解析 Agent 定义。
         *
         * @param pathStr Agent 定义文件的路径字符串
         */
        AgentTemplate(String pathStr) {
            this.path = Path.of(pathStr).toAbsolutePath().normalize();
            // 默认名称取文件名（去除扩展名），对应 Python 的 self.path.stem
            String fileName = this.path.getFileName().toString();
            this.name = fileName.contains(".")
                    ? fileName.substring(0, fileName.lastIndexOf('.'))
                    : fileName;
            parse();
        }

        /**
         * 解析 Markdown 文件中的 YAML frontmatter。
         * <p>
         * 对应 Python 原版的 _parse() 方法。
         * 格式：以 --- 包裹的头部键值对 + 正文。
         * 如果没有 frontmatter，整个文件内容作为 system_prompt。
         */
        private void parse() {
            String text;
            try {
                text = Files.readString(path);
            } catch (Exception e) {
                systemPrompt = "";
                return;
            }

            // 匹配 YAML frontmatter：---\n...\n---\n<body>
            // 对应 Python：re.match(r"^---\s*\n(.*?)\n---\s*\n(.*)", text, re.DOTALL)
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", java.util.regex.Pattern.DOTALL)
                    .matcher(text);

            if (!matcher.find()) {
                // 无 frontmatter，整个文件内容作为系统提示词
                systemPrompt = text.strip();
                return;
            }

            // 解析 frontmatter 中的 key: value 对
            // 对应 Python：for line in match.group(1).splitlines()
            for (String line : matcher.group(1).split("\\R")) {
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String key = line.substring(0, colonIdx).strip();
                    String value = line.substring(colonIdx + 1).strip();
                    config.put(key, value);
                }
            }

            // 正文部分作为系统提示词
            systemPrompt = matcher.group(2).strip();

            // 优先使用 frontmatter 中的 name 字段
            // 对应 Python：self.name = self.config.get("name", self.name)
            if (config.containsKey("name")) {
                name = config.get("name");
            }
        }
    }

    // ==================== 环境变量 & 客户端构建 ====================

    /**
     * 加载 .env 文件并返回统一的环境变量读取接口。
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
     * 支持自定义 baseUrl（用于第三方 API 兼容端点）。
     * 对应 Python 原版的 client = Anthropic(base_url=...) 。
     */
    private static AnthropicClient buildClient() {
        Dotenv dotenv = loadDotenv();

        String baseUrl = dotenv.get("ANTHROPIC_BASE_URL");
        // 对应 Python 原版：使用自定义 base_url 时清除 AUTH_TOKEN，
        // 避免 SDK 优先使用 auth token 而非显式传入的 api key
        if (baseUrl != null && !baseUrl.isBlank()) {
            System.clearProperty("ANTHROPIC_AUTH_TOKEN");
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

    /**
     * 从环境变量加载模型 ID。
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
     * 示例：
     * <pre>
     * defineTool("bash", "Run a shell command.",
     *     Map.of("command", Map.of("type", "string")),
     *     List.of("command"));
     * </pre>
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param properties  JSON Schema 属性定义
     * @param required    必需属性列表
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
     * 防止路径穿越攻击（如 "../../etc/passwd"）。
     * 处理流程：拼接 → 规范化 → 检查前缀。
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

    // ==================== 工具实现（父/子共享） ====================

    /**
     * 执行 shell 命令。
     * <p>
     * 安全特性：危险命令黑名单、120 秒超时、输出截断、OS 自适应。
     * 对应 Python 原版：run_bash(command)。
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
     * 对应 Python 原版：run_read(path, limit)。
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

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 写入文件内容。
     * <p>
     * 安全特性：路径沙箱校验、自动创建父目录。
     * 对应 Python 原版：run_write(path, content)。
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
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 精确文本替换（仅替换第一次出现）。
     * <p>
     * 使用 Pattern.quote() 确保字面量匹配，不会被当作正则表达式。
     * 对应 Python 原版：run_edit(path, old_text, new_text)。
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

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================== JsonValue 转换 ====================

    /**
     * 将 SDK 的 JsonValue 递归转换为普通 Java 对象。
     * <p>
     * 转换优先级：String > Number > Boolean > Map(Object) > List(Array) > null。
     * 用于将工具输入从 JsonValue 类型提取为 Map<String, Object> 以便分发。
     *
     * @param value JsonValue 实例
     * @return 对应的 Java 原生对象（String / Number / Boolean / Map / List / null）
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
        } catch (ClassCastException ignored) {
        }

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
        }

        return null;
    }

    // ==================== 子 Agent 工具定义 ====================

    /**
     * 子 Agent 的 4 个工具（无 task，防止递归生成子 Agent）。
     * <p>
     * 对应 Python 原版的 CHILD_TOOLS 列表。
     * 子 Agent 只能使用基础文件操作工具，不能调用 task 工具来生成孙 Agent，
     * 这样就避免了无限递归的风险。
     */
    private static final List<Tool> CHILD_TOOLS = List.of(
            defineTool("bash", "Run a shell command.",
                    Map.of("command", Map.of("type", "string")),
                    List.of("command")),
            defineTool("read_file", "Read file contents.",
                    Map.of(
                            "path", Map.of("type", "string"),
                            "limit", Map.of("type", "integer")),
                    List.of("path")),
            defineTool("write_file", "Write content to file.",
                    Map.of(
                            "path", Map.of("type", "string"),
                            "content", Map.of("type", "string")),
                    List.of("path", "content")),
            defineTool("edit_file", "Replace exact text in file.",
                    Map.of(
                            "path", Map.of("type", "string"),
                            "old_text", Map.of("type", "string"),
                            "new_text", Map.of("type", "string")),
                    List.of("path", "old_text", "new_text"))
    );

    /**
     * 子 Agent 的工具分发表（工具名 → 处理函数）。
     * <p>
     * 对应 Python 原版的 TOOL_HANDLERS 字典（子 Agent 使用的子集）。
     */
    private static final Map<String, Function<Map<String, Object>, String>> CHILD_HANDLERS = new LinkedHashMap<>();

    static {
        CHILD_HANDLERS.put("bash", input -> {
            String command = (String) input.get("command");
            if (command == null || command.isBlank()) return "Error: command is required";
            return runBash(command);
        });
        CHILD_HANDLERS.put("read_file", input -> {
            String path = (String) input.get("path");
            if (path == null || path.isBlank()) return "Error: path is required";
            Integer limit = null;
            Object limitObj = input.get("limit");
            if (limitObj instanceof Number num) limit = num.intValue();
            return runRead(path, limit);
        });
        CHILD_HANDLERS.put("write_file", input -> {
            String path = (String) input.get("path");
            String content = (String) input.get("content");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (content == null) return "Error: content is required";
            return runWrite(path, content);
        });
        CHILD_HANDLERS.put("edit_file", input -> {
            String path = (String) input.get("path");
            String oldText = (String) input.get("old_text");
            String newText = (String) input.get("new_text");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (oldText == null) return "Error: old_text is required";
            if (newText == null) return "Error: new_text is required";
            return runEdit(path, oldText, newText);
        });
    }

    // ==================== 子 Agent 运行器 ====================

    /**
     * 运行子 Agent：全新上下文，工具集无 task（防递归），最多 30 轮。
     * <p>
     * 这是 S04 的核心方法。对应 Python 原版：run_subagent(prompt)。
     * <p>
     * 实现步骤：
     * <ol>
     *   <li>创建全新的 MessageCreateParams.Builder（空消息历史）</li>
     *   <li>添加用户消息（即父 Agent 传入的 prompt）</li>
     *   <li>用子 Agent 工具集运行 agent 循环（最多 30 轮）</li>
     *   <li>循环结束后，从最后一轮回复中提取纯文本</li>
     *   <li>返回文本摘要（子 Agent 的整个上下文被丢弃）</li>
     * </ol>
     * <p>
     * 关键设计点：
     * - 子 Agent 共享文件系统（同一个 WORK_DIR），但拥有独立的对话历史
     * - 子 Agent 没有 task 工具，所以不能递归生成孙 Agent
     * - 子 Agent 的上下文在返回后被完全丢弃，不会污染父 Agent
     * <p>
     * 对应 Python 原版：
     * <pre>
     * def run_subagent(prompt: str) -> str:
     *     sub_messages = [{"role": "user", "content": prompt}]  # fresh context
     *     for _ in range(30):
     *         response = client.messages.create(...)
     *         sub_messages.append({"role": "assistant", "content": response.content})
     *         if response.stop_reason != "tool_use":
     *             break
     *         # execute tools and append results...
     *     return "".join(b.text for b in response.content if hasattr(b, "text"))
     * </pre>
     *
     * @param client Anthropic API 客户端（与父 Agent 共享同一个）
     * @param model  模型 ID（与父 Agent 使用同一个模型）
     * @param prompt 子 Agent 要处理的任务描述（由父 Agent 的 task 工具传入）
     * @return 子 Agent 的最终文本摘要
     */
    @SuppressWarnings("unchecked")
    private static String runSubagent(AnthropicClient client, String model, String prompt) {
        // 1. 创建全新的 params builder —— 这是上下文隔离的关键
        //    子 Agent 的消息历史从零开始，不继承父 Agent 的任何对话
        var subBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(SUBAGENT_SYSTEM);  // 子 Agent 有自己独立的系统提示词

        // 注册子 Agent 工具（只有 4 个基础工具，没有 task）
        for (Tool tool : CHILD_TOOLS) {
            subBuilder.addTool(tool);
        }

        // 2. 添加用户消息 —— 这就是子 Agent 收到的唯一指令
        subBuilder.addUserMessage(prompt);

        // 3. 子 Agent 循环，最多 30 轮（安全限制，防止无限循环）
        Message lastResponse = null;
        for (int round = 0; round < SUBAGENT_MAX_ROUNDS; round++) {
            Message response = client.messages().create(subBuilder.build());
            lastResponse = response;

            // 将 assistant 回复追加到子 Agent 的消息历史
            subBuilder.addMessage(response);

            // 检查停止原因：如果不是 tool_use，说明子 Agent 已经完成
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                break;  // 子 Agent 停止，跳出循环
            }

            // 4. 执行子 Agent 的工具调用（使用 CHILD_HANDLERS 分发）
            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    String toolName = toolUse.name();

                    // 从 JsonValue 提取输入参数
                    Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());
                    if (input == null) input = Map.of();

                    // 使用子 Agent 的分发表查找处理函数
                    Function<Map<String, Object>, String> handler = CHILD_HANDLERS.get(toolName);
                    String output;
                    if (handler != null) {
                        output = handler.apply(input);
                    } else {
                        output = "Unknown tool: " + toolName;
                    }

                    // 截断过长输出（与 Python 原版的 [:50000] 对齐）
                    if (output.length() > MAX_OUTPUT) {
                        output = output.substring(0, MAX_OUTPUT);
                    }

                    // 构建 tool_result 消息块
                    results.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                }
            }

            // 将工具结果追加为 user 消息（API 要求）
            subBuilder.addUserMessageOfBlockParams(results);
        }

        // 5. 从最后一轮回复中提取纯文本摘要
        //    只取 text 类型的 block，忽略 tool_use block
        //    对应 Python: "".join(b.text for b in response.content if hasattr(b, "text"))
        if (lastResponse == null) {
            return "(no summary)";
        }

        List<String> texts = new ArrayList<>();
        for (ContentBlock block : lastResponse.content()) {
            block.text().ifPresent(textBlock -> texts.add(textBlock.text()));
        }

        // 子 Agent 的整个上下文到这里就被丢弃了
        // 只有这段文本摘要会返回给父 Agent
        return texts.isEmpty() ? "(no summary)" : String.join("", texts);
    }

    // ==================== 父 Agent 循环 ====================

    /**
     * 父 Agent 核心循环：LLM 调用 → 工具执行 → 结果回传。
     * <p>
     * 与 S02 的 agentLoop 相比，关键区别是 task 工具的分支处理：
     * - 当工具名是 "task" 时，不走普通分发表，而是调用 runSubagent()
     * - runSubagent() 返回的摘要文本直接作为 tool_result 返回给父 LLM
     * - 其他工具照常走分发表
     * <p>
     * 对应 Python 原版：agent_loop(messages) 函数。
     *
     * @param client       Anthropic API 客户端
     * @param model        模型 ID
     * @param paramsBuilder 消息创建参数构建器（包含父 Agent 的对话历史）
     * @param parentTools  父 Agent 的 5 个工具（4 个基础 + task）
     * @param baseHandlers 基础工具分发表（4 个基础工具的处理函数）
     */
    @SuppressWarnings("unchecked")
    private static void parentAgentLoop(AnthropicClient client, String model,
                                        MessageCreateParams.Builder paramsBuilder,
                                        List<Tool> parentTools,
                                        Map<String, Function<Map<String, Object>, String>> baseHandlers) {
        while (true) {
            // ---- 1. 调用 LLM ----
            Message response = client.messages().create(paramsBuilder.build());

            // ---- 2. 将 assistant 回复追加到对话历史 ----
            paramsBuilder.addMessage(response);

            // ---- 3. 检查是否需要继续执行工具 ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                // 模型决定停止，打印最终文本回复给用户
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(textBlock ->
                            System.out.println(textBlock.text()));
                }
                return;  // 回到 REPL 等待下一个用户输入
            }

            // ---- 4. 遍历 content blocks，执行工具调用 ----
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    String toolName = toolUse.name();
                    Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());
                    if (input == null) input = Map.of();

                    String output;

                    if ("task".equals(toolName)) {
                        // ===== task 工具的特殊处理 =====
                        // 提取 prompt 和 description
                        String desc = (String) input.getOrDefault("description", "subtask");
                        String prompt = (String) input.get("prompt");

                        // 打印任务开始日志
                        System.out.println(bold("> task") + " (" + desc + "): "
                                + (prompt != null ? prompt.substring(0, Math.min(prompt.length(), 80)) : ""));

                        // 调用子 Agent —— 这是 S04 的核心！
                        // 子 Agent 运行在全新上下文中，完成后只返回摘要
                        output = runSubagent(client, model, prompt != null ? prompt : "");

                        // 打印摘要预览
                        System.out.println(dim("  " + output.substring(0, Math.min(output.length(), 200))));
                    } else {
                        // ===== 普通工具的分发处理 =====
                        Function<Map<String, Object>, String> handler = baseHandlers.get(toolName);
                        if (handler != null) {
                            output = handler.apply(input);
                        } else {
                            output = "Unknown tool: " + toolName;
                        }

                        // 打印工具调用日志
                        System.out.println(bold("> " + toolName) + ":");
                        System.out.println(dim("  " + output.substring(0, Math.min(output.length(), 200))));
                    }

                    // 构建 tool_result 消息块
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                }
            }

            // ---- 5. 将工具结果追加为 user 消息 ----
            paramsBuilder.addUserMessageOfBlockParams(toolResults);
        }
    }

    // ==================== 主程序入口 ====================

    /**
     * REPL 主循环：读取用户输入 → 执行父 Agent 循环（含 task 工具的子 Agent 分发）。
     * <p>
     * 整体架构：
     * <pre>
     * 用户输入 → 父 Agent（5 个工具：bash, read, write, edit, task）
     *                  ↓ 调用 task 工具时
     *             子 Agent（4 个工具：bash, read, write, edit）
     *                  ↓ 返回摘要
     *             父 Agent 继续处理
     * </pre>
     * <p>
     * 工具层次：
     * - 父 Agent 工具（5 个）：bash, read_file, write_file, edit_file, task
     * - 子 Agent 工具（4 个）：bash, read_file, write_file, edit_file（无 task，防递归）
     */
    public static void main(String[] args) {
        // ---- 构建客户端和加载模型 ----
        AnthropicClient client = buildClient();
        String model = loadModel();

        // ---- 父 Agent 的 5 个工具定义 ----
        // 4 个基础工具 + task 工具
        List<Tool> parentTools = new ArrayList<>(CHILD_TOOLS);
        parentTools.add(defineTool("task",
                "Spawn a subagent with fresh context. It shares the filesystem but not conversation history.",
                Map.of(
                        "prompt", Map.of("type", "string"),
                        "description", Map.of("type", "string", "description", "Short description of the task")),
                List.of("prompt")));

        // ---- 基础工具分发表（父/子共享的 4 个工具处理函数） ----
        // task 工具在 parentAgentLoop() 中内联处理，不需要注册到分发表
        Map<String, Function<Map<String, Object>, String>> baseHandlers = new LinkedHashMap<>();
        baseHandlers.put("bash", input -> {
            String command = (String) input.get("command");
            if (command == null || command.isBlank()) return "Error: command is required";
            return runBash(command);
        });
        baseHandlers.put("read_file", input -> {
            String path = (String) input.get("path");
            if (path == null || path.isBlank()) return "Error: path is required";
            Integer limit = null;
            Object limitObj = input.get("limit");
            if (limitObj instanceof Number num) limit = num.intValue();
            return runRead(path, limit);
        });
        baseHandlers.put("write_file", input -> {
            String path = (String) input.get("path");
            String content = (String) input.get("content");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (content == null) return "Error: content is required";
            return runWrite(path, content);
        });
        baseHandlers.put("edit_file", input -> {
            String path = (String) input.get("path");
            String oldText = (String) input.get("old_text");
            String newText = (String) input.get("new_text");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (oldText == null) return "Error: old_text is required";
            if (newText == null) return "Error: new_text is required";
            return runEdit(path, oldText, newText);
        });

        // ---- 构建父 Agent 的消息参数 ----
        var paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(SYSTEM_PROMPT);

        for (Tool tool : parentTools) {
            paramsBuilder.addTool(tool);
        }

        // ---- REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println(bold("S04 Subagent") + " — parent tools: bash, read_file, write_file, edit_file, task");
        System.out.println("                 child tools:  bash, read_file, write_file, edit_file (no task)");
        System.out.println("Type 'q' or 'exit' to quit.\n");

        while (true) {
            // 打印提示符（青色 "s04 >>"）
            System.out.print(cyan("s04 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // 追加用户消息到对话历史
            paramsBuilder.addUserMessage(query);

            // 执行父 Agent 循环（含 task 工具的子 Agent 分发逻辑）
            try {
                parentAgentLoop(client, model, paramsBuilder, parentTools, baseHandlers);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }
            System.out.println();  // 每轮结束后空一行，视觉分隔
        }

        System.out.println(dim("Bye!"));
    }
}
