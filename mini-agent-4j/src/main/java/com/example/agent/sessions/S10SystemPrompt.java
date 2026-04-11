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
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S10：系统提示词组装 —— 系统提示词是一条流水线，不是一坨硬编码字符串（完全自包含）。
 * <p>
 * 本章教授一个核心理念：
 * 系统提示词应该由清晰的段落组装而成，而不是写成一个巨大的硬编码块。
 * <p>
 * 组装流水线：
 *   1. core instructions       —— 身份和基本指令
 *   2. tool listing            —— 可用工具列表及其参数
 *   3. skill metadata          —— skills/ 目录下的 SKILL.md 前置元数据
 *   4. memory section          —— .memory/ 目录下的持久化记忆
 *   5. CLAUDE.md chain         —— 用户全局 + 项目根 + 子目录的 CLAUDE.md 链
 *   6. dynamic context         —— 日期、工作目录、模型、平台
 * <p>
 * Builder 将稳定信息（1-5）与频繁变化的信息（6）分离。
 * 一个简单的 DYNAMIC_BOUNDARY 标记使这个分界可见。
 * <p>
 * 关键洞察："系统提示词的构建是一条有边界的流水线，不是一个大字符串。"
 * <p>
 * 本文件零外部依赖（不导入 com.example.agent.*），所有基础设施内联：
 * - buildClient()：构建 Anthropic API 客户端
 * - loadModel()：从环境变量加载模型 ID
 * - defineTool()：构建 SDK Tool 定义
 * - runBash()：执行 shell 命令（OS 自适应、危险命令拦截、超时、输出截断）
 * - runRead()：读取文件内容（行数限制、路径沙箱、输出截断）
 * - runWrite()：写入文件（自动创建目录、路径沙箱）
 * - runEdit()：精确文本替换（单次替换、Pattern.quote 字面量匹配）
 * - safePath()：路径沙箱校验，防止路径穿越
 * - ANSI 输出：终端彩色文本
 * - agentLoop()：核心 LLM 调用 → 工具执行 → 结果回传循环
 * - jsonValueToObject()：JsonValue → 普通 Java 对象转换
 * - SystemPromptBuilder 内部类：6 段落组装器
 * <p>
 * REPL 命令：/prompt, /sections
 * <p>
 * 对应 Python 原版：s10_system_prompt.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s10_system_prompt.py">Python 原版</a>
 */
public class S10SystemPrompt {

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

    /** 静态段落与动态段落的分界标记。
     * <p>
     * 在真正的 Claude Code 中，静态前缀跨轮次缓存以节省 prompt tokens。
     * DYNAMIC_BOUNDARY 之后的内容量每轮重建，不会被缓存。
     */
    private static final String DYNAMIC_BOUNDARY = "=== DYNAMIC_BOUNDARY ===";

    // ==================== ANSI 颜色输出 ====================
    // 终端彩色文本，让 REPL 和工具日志更易读

    private static final String ANSI_RESET  = "\033[0m";
    private static final String ANSI_BOLD   = "\033[1m";
    private static final String ANSI_DIM    = "\033[2m";
    private static final String ANSI_CYAN   = "\033[36m";
    private static final String ANSI_RED    = "\033[31m";

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

    private static String bold(String text) { return ansi(ANSI_BOLD, text); }
    private static String dim(String text)  { return ansi(ANSI_DIM, text); }
    private static String cyan(String text) { return ansi(ANSI_CYAN, text); }
    private static String red(String text)  { return ansi(ANSI_RED, text); }

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
                    Pattern.quote(oldText),
                    Matcher.quoteReplacement(newText));
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

    // ==================== SystemPromptBuilder 内部类 ====================

    /**
     * 系统提示词组装器：将提示词拆分为 6 个独立段落，每个段落职责单一。
     * <p>
     * 教学目标：让提示词更容易推理、更容易测试、更容易演进。
     * <p>
     * 6 个段落：
     * <ol>
     *   <li>_buildCore()         —— 身份和基本指令</li>
     *   <li>_buildToolListing()  —— 可用工具列表</li>
     *   <li>_buildSkillListing() —— skills/ 下的技能元数据</li>
     *   <li>_buildMemorySection()—— .memory/ 下的持久化记忆</li>
     *   <li>_buildClaudeMd()     —— CLAUDE.md 链（用户全局 + 项目 + 子目录）</li>
     *   <li>_buildDynamicContext()—— 动态上下文（日期、平台、模型等）</li>
     * </ol>
     * <p>
     * 段落 1-5 是稳定内容，可以跨轮次缓存；段落 6 每轮变化。
     * DYNAMIC_BOUNDARY 标记在两组之间。
     * <p>
     * 对应 Python 原版：SystemPromptBuilder 类。
     */
    static class SystemPromptBuilder {

        /** 工作目录，用于定位文件系统资源 */
        private final Path workdir;

        /** 工具定义列表，用于生成工具清单段落 */
        private final List<Tool> tools;

        /** 技能目录 */
        private final Path skillsDir;

        /** 记忆目录 */
        private final Path memoryDir;

        /** 模型 ID，用于动态上下文段落 */
        private final String model;

        /** 用于解析 SKILL.md / .memory/*.md 前置元数据的正则 */
        private static final Pattern FRONTMATTER_PATTERN =
                Pattern.compile("^---\\s*\\n(.*?)\\n---", Pattern.DOTALL);

        /** 用于解析记忆文件的前置元数据 + 正文正则 */
        private static final Pattern MEMORY_FRONTMATTER_PATTERN =
                Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);

        /**
         * 构建系统提示词组装器。
         *
         * @param workdir 工作目录
         * @param tools   工具定义列表
         * @param model   模型 ID
         */
        SystemPromptBuilder(Path workdir, List<Tool> tools, String model) {
            this.workdir = workdir;
            this.tools = tools != null ? tools : List.of();
            this.skillsDir = workdir.resolve("skills");
            this.memoryDir = workdir.resolve(".memory");
            this.model = model;
        }

        // ---- 段落 1：核心指令 ----

        /**
         * 构建核心身份和基本指令段落。
         * <p>
         * 告诉模型它是谁、在哪工作、应该如何行动。
         * <p>
         * 对应 Python 原版：_build_core() 方法。
         *
         * @return 核心指令文本
         */
        private String _buildCore() {
            return "You are a coding agent operating in " + workdir + ".\n"
                 + "Use the provided tools to explore, read, write, and edit files.\n"
                 + "Always verify before assuming. Prefer reading files over guessing.";
        }

        // ---- 段落 2：工具清单 ----

        /**
         * 构建可用工具清单段落。
         * <p>
         * 遍历工具定义列表，为每个工具提取名称、参数和描述，
         * 生成结构化的工具列表供模型参考。
         * <p>
         * 对应 Python 原版：_build_tool_listing() 方法。
         *
         * @return 工具清单文本，无工具时返回空字符串
         */
        @SuppressWarnings("unchecked")
        private String _buildToolListing() {
            if (tools.isEmpty()) {
                return "";
            }

            List<String> lines = new ArrayList<>();
            lines.add("# Available tools");

            for (Tool tool : tools) {
                // 从 InputSchema 中提取属性名列表
                // SDK 方法：inputSchema() 返回 Tool.InputSchema，_properties() 返回 JsonValue
                String params = "";
                Tool.InputSchema schema = tool.inputSchema();
                if (schema != null) {
                    JsonValue propsJson = schema._properties();
                    if (propsJson != null) {
                        Object propsObj = jsonValueToObject(propsJson);
                        if (propsObj instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> props = (Map<String, Object>) propsObj;
                            params = String.join(", ", props.keySet());
                        }
                    }
                }

                String desc = tool.description().orElse("");
                lines.add("- " + tool.name() + "(" + params + "): " + desc);
            }

            return String.join("\n", lines);
        }

        // ---- 段落 3：技能元数据 ----

        /**
         * 构建技能元数据段落（Layer 1 概念，来自 s05）。
         * <p>
         * 扫描 skills/ 目录下的子目录，查找 SKILL.md 文件，
         * 解析前置元数据（frontmatter）中的 name 和 description 字段，
         * 生成技能名称和描述列表。
         * <p>
         * 对应 Python 原版：_build_skill_listing() 方法。
         *
         * @return 技能列表文本，无技能时返回空字符串
         */
        private String _buildSkillListing() {
            if (!Files.isDirectory(skillsDir)) {
                return "";
            }

            List<String> skills = new ArrayList<>();

            try {
                // 按目录名排序，确保输出稳定
                List<Path> sortedDirs = new ArrayList<>();
                try (var stream = Files.list(skillsDir)) {
                    stream.filter(Files::isDirectory)
                          .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                          .forEach(sortedDirs::add);
                }

                for (Path skillDir : sortedDirs) {
                    Path skillMd = skillDir.resolve("SKILL.md");
                    if (!Files.exists(skillMd)) {
                        continue;
                    }

                    String text = Files.readString(skillMd);

                    // 解析前置元数据（frontmatter）：---\nkey: value\n---
                    Matcher matcher = FRONTMATTER_PATTERN.matcher(text);
                    if (!matcher.find()) {
                        continue;
                    }

                    Map<String, String> meta = parseFrontmatter(matcher.group(1));
                    String name = meta.getOrDefault("name", skillDir.getFileName().toString());
                    String desc = meta.getOrDefault("description", "");
                    skills.add("- " + name + ": " + desc);
                }
            } catch (Exception e) {
                // 读取 skills 目录失败时静默忽略
            }

            if (skills.isEmpty()) {
                return "";
            }
            return "# Available skills\n" + String.join("\n", skills);
        }

        // ---- 段落 4：记忆内容 ----

        /**
         * 构建记忆段落。
         * <p>
         * 扫描 .memory/ 目录下的 .md 文件，跳过 MEMORY.md 本身，
         * 解析每个文件的前置元数据（frontmatter）中的 name、type、description，
         * 以及正文内容。
         * <p>
         * 对应 Python 原版：_build_memory_section() 方法。
         *
         * @return 记忆内容文本，无记忆时返回空字符串
         */
        private String _buildMemorySection() {
            if (!Files.isDirectory(memoryDir)) {
                return "";
            }

            List<String> memories = new ArrayList<>();

            try {
                // 按文件名排序，确保输出稳定
                List<Path> sortedFiles = new ArrayList<>();
                try (var stream = Files.newDirectoryStream(memoryDir, "*.md")) {
                    for (Path mdFile : stream) {
                        // 跳过 MEMORY.md 本身（与 Python 原版行为一致）
                        if (mdFile.getFileName().toString().equals("MEMORY.md")) {
                            continue;
                        }
                        sortedFiles.add(mdFile);
                    }
                }
                sortedFiles.sort(Comparator.comparing(p -> p.getFileName().toString()));

                for (Path mdFile : sortedFiles) {
                    String text = Files.readString(mdFile);

                    // 解析前置元数据 + 正文
                    Matcher matcher = MEMORY_FRONTMATTER_PATTERN.matcher(text);
                    if (!matcher.find()) {
                        continue;
                    }

                    String header = matcher.group(1);
                    String body = matcher.group(2).trim();

                    Map<String, String> meta = parseFrontmatter(header);
                    String name = meta.getOrDefault("name", mdFile.getFileName().toString().replace(".md", ""));
                    String memType = meta.getOrDefault("type", "project");
                    String desc = meta.getOrDefault("description", "");

                    memories.add("[" + memType + "] " + name + ": " + desc + "\n" + body);
                }
            } catch (Exception e) {
                // 读取 .memory 目录失败时静默忽略
            }

            if (memories.isEmpty()) {
                return "";
            }
            return "# Memories (persistent)\n\n" + String.join("\n\n", memories);
        }

        // ---- 段落 5：CLAUDE.md 链 ----

        /**
         * 构建 CLAUDE.md 指令链段落。
         * <p>
         * 按优先级顺序加载所有 CLAUDE.md 文件（全部包含，不互斥）：
         * <ol>
         *   <li>~/.claude/CLAUDE.md —— 用户全局指令</li>
         *   <li>&lt;project-root&gt;/CLAUDE.md —— 项目根目录指令</li>
         *   <li>&lt;current-subdir&gt;/CLAUDE.md —— 子目录特定指令</li>
         * </ol>
         * <p>
         * 对应 Python 原版：_build_claude_md() 方法。
         *
         * @return CLAUDE.md 指令链文本，无文件时返回空字符串
         */
        private String _buildClaudeMd() {
            List<Map.Entry<String, String>> sources = new ArrayList<>();

            // 用户全局指令：~/.claude/CLAUDE.md
            Path userHome = Path.of(System.getProperty("user.home"));
            Path userClaude = userHome.resolve(".claude").resolve("CLAUDE.md");
            if (Files.exists(userClaude)) {
                try {
                    String content = Files.readString(userClaude);
                    sources.add(new AbstractMap.SimpleEntry<>(
                            "user global (~/.claude/CLAUDE.md)", content));
                } catch (Exception ignored) {
                    // 读取失败时静默忽略
                }
            }

            // 项目根目录：CLAUDE.md
            Path projectClaude = workdir.resolve("CLAUDE.md");
            if (Files.exists(projectClaude)) {
                try {
                    String content = Files.readString(projectClaude);
                    sources.add(new AbstractMap.SimpleEntry<>(
                            "project root (CLAUDE.md)", content));
                } catch (Exception ignored) {
                    // 读取失败时静默忽略
                }
            }

            // 子目录：如果 cwd 与 workdir 不同，检查 cwd 下的 CLAUDE.md
            Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            if (!cwd.equals(workdir)) {
                Path subdirClaude = cwd.resolve("CLAUDE.md");
                if (Files.exists(subdirClaude)) {
                    try {
                        String content = Files.readString(subdirClaude);
                        sources.add(new AbstractMap.SimpleEntry<>(
                                "subdir (" + cwd.getFileName() + "/CLAUDE.md)", content));
                    } catch (Exception ignored) {
                        // 读取失败时静默忽略
                    }
                }
            }

            if (sources.isEmpty()) {
                return "";
            }

            List<String> parts = new ArrayList<>();
            parts.add("# CLAUDE.md instructions");
            for (var entry : sources) {
                parts.add("## From " + entry.getKey());
                parts.add(entry.getValue().trim());
            }
            return String.join("\n\n", parts);
        }

        // ---- 段落 6：动态上下文 ----

        /**
         * 构建动态上下文段落。
         * <p>
         * 包含每轮可能变化的信息：
         * - 当前日期
         * - 工作目录
         * - 模型 ID
         * - 操作系统平台
         * <p>
         * 对应 Python 原版：_build_dynamic_context() 方法。
         *
         * @return 动态上下文文本
         */
        private String _buildDynamicContext() {
            List<String> lines = new ArrayList<>();
            lines.add("Current date: " + LocalDate.now().toString());
            lines.add("Working directory: " + workdir);
            lines.add("Model: " + model);
            lines.add("Platform: " + System.getProperty("os.name"));
            return "# Dynamic context\n" + String.join("\n", lines);
        }

        // ---- 前置元数据解析辅助 ----

        /**
         * 解析前置元数据（frontmatter）为键值对 Map。
         * <p>
         * frontmatter 格式为 YAML 子集：
         * <pre>
         * key1: value1
         * key2: value2
         * </pre>
         * 每行按第一个冒号分割，trim 后存入 Map。
         *
         * @param frontmatterText frontmatter 文本（不含 --- 分隔符）
         * @return 键值对 Map
         */
        private static Map<String, String> parseFrontmatter(String frontmatterText) {
            Map<String, String> meta = new LinkedHashMap<>();
            for (String line : frontmatterText.split("\n")) {
                int colonIdx = line.indexOf(':');
                if (colonIdx > 0) {
                    String key = line.substring(0, colonIdx).trim();
                    String value = line.substring(colonIdx + 1).trim();
                    meta.put(key, value);
                }
            }
            return meta;
        }

        // ---- 组装所有段落 ----

        /**
         * 组装完整的系统提示词。
         * <p>
         * 将 6 个段落按顺序拼接，段落之间用空行分隔。
         * 静态段落（1-5）与动态段落（6）之间插入 DYNAMIC_BOUNDARY 标记。
         * <p>
         * 在真正的 Claude Code 中，静态前缀跨轮次缓存以节省 prompt tokens，
         * 只有 DYNAMIC_BOUNDARY 之后的内容每轮重建。
         * <p>
         * 对应 Python 原版：build() 方法。
         *
         * @return 完整的系统提示词字符串
         */
        public String build() {
            List<String> sections = new ArrayList<>();

            // 段落 1：核心指令（始终存在）
            String core = _buildCore();
            if (!core.isEmpty()) {
                sections.add(core);
            }

            // 段落 2：工具清单
            String toolListing = _buildToolListing();
            if (!toolListing.isEmpty()) {
                sections.add(toolListing);
            }

            // 段落 3：技能元数据
            String skillListing = _buildSkillListing();
            if (!skillListing.isEmpty()) {
                sections.add(skillListing);
            }

            // 段落 4：记忆内容
            String memory = _buildMemorySection();
            if (!memory.isEmpty()) {
                sections.add(memory);
            }

            // 段落 5：CLAUDE.md 链
            String claudeMd = _buildClaudeMd();
            if (!claudeMd.isEmpty()) {
                sections.add(claudeMd);
            }

            // 静态 / 动态分界标记
            sections.add(DYNAMIC_BOUNDARY);

            // 段落 6：动态上下文（始终存在）
            String dynamic = _buildDynamicContext();
            if (!dynamic.isEmpty()) {
                sections.add(dynamic);
            }

            return String.join("\n\n", sections);
        }
    }

    // ==================== Agent 核心循环 ====================

    /**
     * Agent 核心循环：LLM 调用 → 工具执行 → 结果回传。
     * <p>
     * 这是整个 Agent 系统的心脏。核心模式极其简单：
     * <pre>
     *   while (stopReason == TOOL_USE) {
     *       response = LLM(messages, tools);
     *       execute tools;
     *       append results;
     *   }
     * </pre>
     * <p>
     * 与 S02 的区别：每次循环迭代都重新构建系统提示词（而非使用静态字符串），
     * 以反映最新的动态上下文。在真正的 Claude Code 中，静态前缀会跨轮次缓存。
     * <p>
     * 对应 Python 原版：agent_loop(messages) 函数。
     *
     * @param client       Anthropic API 客户端
     * @param model        模型 ID
     * @param paramsBuilder 消息创建参数构建器（包含已有对话历史）
     * @param tools        工具定义列表（发送给 LLM）
     * @param toolHandlers 工具分发表：工具名 → 处理函数
     * @param promptBuilder 系统提示词组装器（每轮重建）
     */
    @SuppressWarnings("unchecked")
    private static void agentLoop(AnthropicClient client, String model,
                                  MessageCreateParams.Builder paramsBuilder,
                                  List<Tool> tools,
                                  Map<String, Function<Map<String, Object>, String>> toolHandlers,
                                  SystemPromptBuilder promptBuilder) {
        while (true) {
            // ---- 0. 每轮重建系统提示词 ----
            // 在真正的 Claude Code 中，静态前缀跨轮次缓存，只有动态部分重建
            String system = promptBuilder.build();
            paramsBuilder.system(system);

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

            // ---- 4. 遍历 content blocks，执行工具调用 ----
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    String toolName = toolUse.name();

                    // 从 JsonValue 提取输入参数（转换为 Map<String, Object>）
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

                    // 打印工具调用日志：> toolName: 输出预览
                    System.out.println(bold("> " + toolName) + ":");
                    System.out.println(dim("  " + output.substring(0, Math.min(output.length(), 200))));

                    // 构造 tool_result 消息块，回传给 LLM
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                }
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
     * while True:
     *     query = input("s10 >> ")     # 读取用户输入
     *     messages.append(query)       # 追加到历史
     *     agent_loop(messages)         # 执行 Agent 循环（每轮重建 system prompt）
     * </pre>
     * <p>
     * 与 S02 的区别：
     * - 系统提示词由 SystemPromptBuilder 组装（非硬编码字符串）
     * - 每轮 Agent 循环重建系统提示词
     * - 支持 /prompt 和 /sections REPL 命令
     */
    public static void main(String[] args) {
        // ---- 构建客户端和加载模型 ----
        AnthropicClient client = buildClient();
        String model = loadModel();

        // ---- 定义 4 个工具 ----
        // bash, read_file, write_file, edit_file —— 与 S02 相同
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

        // ---- 构建系统提示词组装器 ----
        // 对应 Python 原版的 prompt_builder = SystemPromptBuilder(workdir=WORKDIR, tools=TOOLS)
        SystemPromptBuilder promptBuilder = new SystemPromptBuilder(WORK_DIR, tools, model);

        // ---- 启动时展示系统提示词概要（教学目的） ----
        String fullPrompt = promptBuilder.build();
        int sectionCount = 0;
        for (String line : fullPrompt.split("\n")) {
            if (line.startsWith("# ") || line.equals(DYNAMIC_BOUNDARY)) {
                sectionCount++;
            }
        }
        System.out.println(bold("S10 System Prompt")
                + " — assembled: " + fullPrompt.length() + " chars, ~" + sectionCount + " sections");
        System.out.println("Type 'q' or 'exit' to quit. Commands: /prompt, /sections\n");

        // ---- 构建消息参数（包含模型、工具、maxTokens） ----
        // 注意：system 由 promptBuilder 每轮动态设置，不在此处指定
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L);

        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }

        // ---- REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);

        while (true) {
            // 打印提示符（青色 "s10 >>"）
            System.out.print(cyan("s10 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();
            // 空输入或退出命令 → 结束
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // /prompt 命令：显示完整组装的系统提示词
            if (query.equals("/prompt")) {
                System.out.println("--- System Prompt ---");
                System.out.println(promptBuilder.build());
                System.out.println("--- End ---");
                continue;
            }

            // /sections 命令：只显示段落标题行
            if (query.equals("/sections")) {
                String prompt = promptBuilder.build();
                for (String line : prompt.split("\n")) {
                    if (line.startsWith("# ") || line.equals(DYNAMIC_BOUNDARY)) {
                        System.out.println("  " + line);
                    }
                }
                continue;
            }

            // 追加用户消息到对话历史
            paramsBuilder.addUserMessage(query);

            // 执行 Agent 循环（LLM 调用 + 工具执行，每轮重建 system prompt）
            try {
                agentLoop(client, model, paramsBuilder, tools, toolHandlers, promptBuilder);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }
            System.out.println(); // 每轮结束后空一行，视觉分隔
        }

        System.out.println(dim("Bye!"));
    }
}
