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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S07：权限系统 —— 完全自包含实现（不依赖 core/、tools/、util/ 包）。
 * <p>
 * 每次工具调用都经过权限管线后才会真正执行。
 * <p>
 * 权限管线教学顺序：
 *   1. deny 规则（拒绝规则，优先级最高，不可绕过）
 *   2. mode 检查（default / plan / auto 三种模式）
 *   3. allow 规则（允许规则）
 *   4. ask user（无规则匹配时询问用户）
 * <p>
 * 关键洞察："安全是一个管线，不是一个布尔值。"
 * <p>
 * 本文件将所有基础设施内联：
 * - BashSecurityValidator：基于正则的危险命令校验器（内部类）
 * - PermissionManager：权限管线管理器（内部类）
 * - buildClient()：构建 Anthropic API 客户端
 * - loadModel()：从环境变量加载模型 ID
 * - defineTool()：构建 SDK Tool 定义
 * - runBash()：执行 shell 命令（OS 自适应、超时、输出截断）
 * - runRead()：读取文件内容（行数限制、路径沙箱、输出截断）
 * - runWrite()：写入文件（自动创建目录、路径沙箱）
 * - runEdit()：精确文本替换（单次替换、Pattern.quote 字面量匹配）
 * - safePath()：路径沙箱校验，防止路径穿越
 * - ANSI 输出：终端彩色文本
 * - agentLoop()：权限感知的 Agent 循环（每次工具调用前检查权限）
 * - jsonValueToObject()：JsonValue → 普通 Java 对象转换
 * <p>
 * 对应 Python 原版：s07_permission_system.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s07_permission_system.py">Python 原版</a>
 */
public class S07PermissionSystem {

    // ==================== 常量 ====================

    /** 最大输出长度（字符），与 Python 原版 50000 对齐 */
    private static final int MAX_OUTPUT = 50000;

    /** bash 命令超时（秒），与 Python 原版 120s 对齐 */
    private static final int BASH_TIMEOUT = 120;

    /** 工作目录（Agent 的文件操作沙箱根目录） */
    private static final Path WORK_DIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /** 系统提示词：告诉模型它是一个编码 Agent，用户控制权限，某些工具调用可能被拒绝 */
    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + WORK_DIR + ". Use tools to solve tasks.\n"
            + "The user controls permissions. Some tool calls may be denied.";

    /** 权限模式列表：default（默认询问）、plan（只读，拒绝写操作）、auto（自动允许读，询问写） */
    private static final List<String> MODES = List.of("default", "plan", "auto");

    /** 只读工具集合 —— plan 模式下允许、auto 模式下自动批准 */
    private static final Set<String> READ_ONLY_TOOLS = Set.of("read_file");

    /** 写操作工具集合 —— plan 模式下拒绝 */
    private static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file", "bash");

    // ==================== ANSI 颜色输出 ====================
    // 终端彩色文本，让 REPL 和工具日志更易读

    private static final String ANSI_RESET  = "\033[0m";
    private static final String ANSI_BOLD   = "\033[1m";
    private static final String ANSI_DIM    = "\033[2m";
    private static final String ANSI_CYAN   = "\033[36m";
    private static final String ANSI_RED    = "\033[31m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_GREEN  = "\033[32m";

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
    private static String green(String text)  { return ansi(ANSI_GREEN, text); }

    // ==================== 内部类：BashSecurityValidator ====================

    /**
     * Bash 命令安全校验器。
     * <p>
     * 基于正则表达式检测明显的危险命令模式。
     * 教学版本故意保持精简，便于阅读。
     * 先捕获高风险模式，再交给权限管线决定拒绝还是询问用户。
     * <p>
     * 校验器列表：
     * - shell_metachar：shell 元字符（分号、管道、反引号、美元符等）
     * - sudo：提权命令
     * - rm_rf：递归删除
     * - cmd_substitution：命令替换 $(...)
     * - ifs_injection：IFS 变量注入
     */
    static class BashSecurityValidator {

        /**
         * 校验器定义：名称 + 正则模式。
         * 每个校验器独立检查，所有失败项都会被收集返回。
         */
        private static final List<AbstractMap.SimpleEntry<String, Pattern>> VALIDATORS = List.of(
                // shell 元字符：分号、&、管道、反引号、美元符
                new AbstractMap.SimpleEntry<>("shell_metachar",
                        Pattern.compile("[;&|`$]")),
                // 提权命令：sudo
                new AbstractMap.SimpleEntry<>("sudo",
                        Pattern.compile("\\bsudo\\b")),
                // 递归删除：rm -rf 等变体
                new AbstractMap.SimpleEntry<>("rm_rf",
                        Pattern.compile("\\brm\\s+(-[a-zA-Z]*)?r")),
                // 命令替换：$(...)
                new AbstractMap.SimpleEntry<>("cmd_substitution",
                        Pattern.compile("\\$\\(")),
                // IFS 变量注入：IFS=...
                new AbstractMap.SimpleEntry<>("ifs_injection",
                        Pattern.compile("\\bIFS\\s*="))
        );

        /** 严重模式集合 —— 这些模式直接拒绝，不询问用户 */
        private static final Set<String> SEVERE_PATTERNS = Set.of("sudo", "rm_rf");

        /**
         * 校验 bash 命令，返回所有校验失败项。
         * <p>
         * 返回列表中的每一项是 [校验器名称, 匹配到的正则模式字符串]。
         * 空列表表示命令通过了所有校验。
         *
         * @param command 要校验的 bash 命令
         * @return 失败项列表，每一项为 [名称, 模式字符串]
         */
        public List<String[]> validate(String command) {
            List<String[]> failures = new ArrayList<>();
            for (var validator : VALIDATORS) {
                String name = validator.getKey();
                Pattern pattern = validator.getValue();
                if (pattern.matcher(command).find()) {
                    failures.add(new String[]{name, pattern.pattern()});
                }
            }
            return failures;
        }

        /**
         * 便捷方法：命令是否安全（无任何校验器触发）。
         *
         * @param command 要校验的命令
         * @return true 表示安全（所有校验通过）
         */
        public boolean isSafe(String command) {
            return validate(command).isEmpty();
        }

        /**
         * 人类可读的校验失败描述。
         * <p>
         * 将所有失败项格式化为 "Security flags: name1 (pattern: xxx), name2 (pattern: yyy)" 的形式。
         *
         * @param command 要描述的命令
         * @return 失败描述字符串
         */
        public String describeFailures(String command) {
            List<String[]> failures = validate(command);
            if (failures.isEmpty()) {
                return "No issues detected";
            }
            List<String> parts = new ArrayList<>();
            for (String[] f : failures) {
                parts.add(f[0] + " (pattern: " + f[1] + ")");
            }
            return "Security flags: " + String.join(", ", parts);
        }

        /**
         * 检查失败项中是否包含严重模式（sudo、rm_rf）。
         *
         * @param failures 校验失败列表
         * @return true 表示包含至少一个严重模式
         */
        public boolean hasSevereFailure(List<String[]> failures) {
            for (String[] f : failures) {
                if (SEVERE_PATTERNS.contains(f[0])) {
                    return true;
                }
            }
            return false;
        }
    }

    // ==================== 内部类：PermissionManager ====================

    /**
     * 权限管线管理器。
     * <p>
     * 管线顺序：deny 规则 → mode 检查 → allow 规则 → ask user
     * <p>
     * 三种模式：
     * - default：无规则匹配时询问用户
     * - plan：拒绝所有写操作，允许读操作
     * - auto：自动允许读操作，写操作需要询问
     * <p>
     * 教学版本故意保持管线简短，读者可以在理解后再添加更高级的策略层。
     * <p>
     * 熔断器：跟踪连续拒绝次数，达到阈值（默认 3 次）时发出警告。
     */
    static class PermissionManager {

        /** 默认权限规则列表。
         * 规则格式：tool（工具名或 "*"）、path（glob 模式或 "*"）、content（内容匹配或无）、behavior（allow/deny/ask）
         * 规则按顺序检查，先匹配到的生效。
         */
        private static final List<Map<String, String>> DEFAULT_RULES = List.of(
                // 始终拒绝危险模式
                Map.of("tool", "bash", "content", "rm -rf /", "behavior", "deny"),
                Map.of("tool", "bash", "content", "sudo *", "behavior", "deny"),
                // 允许读取任何文件
                Map.of("tool", "read_file", "path", "*", "behavior", "allow")
        );

        /** 当前权限模式 */
        String mode;

        /** 当前生效的规则列表（可动态追加，例如 "always" 回答会添加 allow 规则） */
        List<Map<String, String>> rules;

        /** 连续拒绝计数（用于熔断器） */
        int consecutiveDenials;

        /** 连续拒绝阈值（达到此值时发出警告） */
        int maxConsecutiveDenials;

        /**
         * 构造权限管理器。
         *
         * @param mode  权限模式（default / plan / auto）
         * @param rules 自定义规则列表，null 时使用默认规则
         */
        PermissionManager(String mode, List<Map<String, String>> rules) {
            if (!MODES.contains(mode)) {
                throw new IllegalArgumentException(
                        "Unknown mode: " + mode + ". Choose from " + MODES);
            }
            this.mode = mode;
            this.rules = rules != null ? new ArrayList<>(rules) : new ArrayList<>(DEFAULT_RULES);
            this.consecutiveDenials = 0;
            this.maxConsecutiveDenials = 3;
        }

        /**
         * 权限检查入口。
         * <p>
         * 返回 Map 包含两个字段：
         * - behavior："allow"（允许）、"deny"（拒绝）、"ask"（询问用户）
         * - reason：决策原因的人类可读描述
         * <p>
         * 管线步骤：
         * 1. Bash 安全校验（仅对 bash 工具）
         *    - 严重模式（sudo、rm_rf）→ 直接 deny
         *    - 其他模式 → ask
         * 2. deny 规则（不可绕过，始终优先检查）
         * 3. mode 检查（plan 拒绝写操作，auto 自动允许读操作）
         * 4. allow 规则
         * 5. ask user（默认行为，无规则匹配时询问）
         *
         * @param toolName  工具名称
         * @param toolInput 工具输入参数
         * @param bashValidator Bash 安全校验器实例
         * @return 决策结果 {behavior, reason}
         */
        Map<String, String> check(String toolName, Map<String, Object> toolInput,
                                  BashSecurityValidator bashValidator) {
            // ---- Step 0: Bash 安全校验（在 deny 规则之前执行）----
            // 教学版本提前检查，逻辑更清晰
            if ("bash".equals(toolName)) {
                String command = (String) toolInput.getOrDefault("command", "");
                List<String[]> failures = bashValidator.validate(command);
                if (!failures.isEmpty()) {
                    // 严重模式（sudo、rm_rf）直接拒绝
                    if (bashValidator.hasSevereFailure(failures)) {
                        String desc = bashValidator.describeFailures(command);
                        return Map.of("behavior", "deny",
                                "reason", "Bash validator: " + desc);
                    }
                    // 其他模式升级为询问用户（用户仍可手动批准）
                    String desc = bashValidator.describeFailures(command);
                    return Map.of("behavior", "ask",
                            "reason", "Bash validator flagged: " + desc);
                }
            }

            // ---- Step 1: deny 规则（不可绕过，始终优先检查）----
            for (Map<String, String> rule : rules) {
                if (!"deny".equals(rule.get("behavior"))) continue;
                if (matches(rule, toolName, toolInput)) {
                    return Map.of("behavior", "deny",
                            "reason", "Blocked by deny rule: " + rule);
                }
            }

            // ---- Step 2: 基于模式的决策 ----
            if ("plan".equals(mode)) {
                // plan 模式：拒绝所有写操作，允许读操作
                if (WRITE_TOOLS.contains(toolName)) {
                    return Map.of("behavior", "deny",
                            "reason", "Plan mode: write operations are blocked");
                }
                return Map.of("behavior", "allow",
                        "reason", "Plan mode: read-only allowed");
            }

            if ("auto".equals(mode)) {
                // auto 模式：自动允许只读工具，写操作继续检查 allow 规则和询问
                if (READ_ONLY_TOOLS.contains(toolName)) {
                    return Map.of("behavior", "allow",
                            "reason", "Auto mode: read-only tool auto-approved");
                }
                // 写操作：fall through 到 allow 规则和 ask user
            }

            // ---- Step 3: allow 规则 ----
            for (Map<String, String> rule : rules) {
                if (!"allow".equals(rule.get("behavior"))) continue;
                if (matches(rule, toolName, toolInput)) {
                    consecutiveDenials = 0;
                    return Map.of("behavior", "allow",
                            "reason", "Matched allow rule: " + rule);
                }
            }

            // ---- Step 4: ask user（无规则匹配时的默认行为）----
            return Map.of("behavior", "ask",
                    "reason", "No rule matched for " + toolName + ", asking user");
        }

        /**
         * 交互式用户审批提示。
         * <p>
         * 支持 y/n/always 三种回答：
         * - y/yes：本次允许
         * - n/其他：本次拒绝
         * - always：永久添加该工具的 allow 规则
         * <p>
         * 熔断器：连续拒绝达到阈值时发出警告，建议切换到 plan 模式。
         *
         * @param toolName  工具名称
         * @param toolInput 工具输入参数
         * @return true 表示用户批准，false 表示用户拒绝
         */
        boolean askUser(String toolName, Map<String, Object> toolInput) {
            // 预览工具输入（截断到 200 字符）
            String preview = toolInput.toString();
            if (preview.length() > 200) {
                preview = preview.substring(0, 200);
            }
            System.out.println("\n  [Permission] " + toolName + ": " + preview);
            System.out.print("  Allow? (y/n/always): ");

            Scanner sc = new Scanner(System.in);
            String answer;
            try {
                if (!sc.hasNextLine()) return false;
                answer = sc.nextLine().trim().toLowerCase();
            } catch (Exception e) {
                return false;
            }

            if ("always".equals(answer)) {
                // 添加永久 allow 规则
                rules.add(new HashMap<>(Map.of("tool", toolName, "path", "*", "behavior", "allow")));
                consecutiveDenials = 0;
                return true;
            }
            if ("y".equals(answer) || "yes".equals(answer)) {
                consecutiveDenials = 0;
                return true;
            }

            // 拒绝时累加连续拒绝计数（熔断器）
            consecutiveDenials++;
            if (consecutiveDenials >= maxConsecutiveDenials) {
                System.out.println(yellow("  [" + consecutiveDenials
                        + " consecutive denials -- consider switching to plan mode]"));
            }
            return false;
        }

        /**
         * 检查规则是否匹配当前工具调用。
         * <p>
         * 匹配逻辑：
         * - tool 字段：工具名精确匹配，"*" 匹配所有
         * - path 字段（可选）：对工具输入的 path 参数做 glob 匹配
         * - content 字段（可选）：对工具输入的 command 参数做 glob 匹配（用于 bash 工具）
         *
         * @param rule      权限规则
         * @param toolName  工具名称
         * @param toolInput 工具输入参数
         * @return true 表示规则匹配
         */
        private boolean matches(Map<String, String> rule, String toolName,
                                Map<String, Object> toolInput) {
            // 工具名匹配
            String ruleTool = rule.get("tool");
            if (ruleTool != null && !"*".equals(ruleTool)) {
                if (!ruleTool.equals(toolName)) {
                    return false;
                }
            }
            // 路径模式匹配（glob）
            String rulePath = rule.get("path");
            if (rulePath != null && !"*".equals(rulePath)) {
                String inputPath = String.valueOf(toolInput.getOrDefault("path", ""));
                if (!globMatch(inputPath, rulePath)) {
                    return false;
                }
            }
            // 内容模式匹配（用于 bash 命令的 glob 匹配）
            String ruleContent = rule.get("content");
            if (ruleContent != null) {
                String command = String.valueOf(toolInput.getOrDefault("command", ""));
                if (!globMatch(command, ruleContent)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 简易 glob 模式匹配（支持 * 通配符）。
         * <p>
         * 将 glob 模式转换为正则表达式：
         * - * → .* （匹配任意字符序列）
         * - 其他字符转义后字面量匹配
         *
         * @param text   待匹配文本
         * @param pattern glob 模式（支持 *）
         * @return true 表示匹配
         */
        private boolean globMatch(String text, String pattern) {
            // 将 glob 模式转换为正则表达式
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') {
                    regex.append(".*");
                } else {
                    regex.append(Pattern.quote(String.valueOf(c)));
                }
            }
            return Pattern.compile(regex.toString()).matcher(text).matches();
        }
    }

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

    /**
     * 检查工作区是否被标记为受信任。
     * <p>
     * 对应 Python 原版：is_workspace_trusted(workspace)。
     * 通过检查 .miniclaude/.claude_trusted 标记文件判断工作区是否已被用户明确信任。
     * 教学版本使用简单的标记文件机制，更完整的系统可在此基础上叠加更丰富的信任流程。
     *
     * @param workspace 工作区路径，null 时使用 WORK_DIR
     * @return true 表示工作区已被标记为受信任
     */
    private static boolean isWorkspaceTrusted(Path workspace) {
        Path ws = workspace != null ? workspace : WORK_DIR;
        Path trustMarker = ws.resolve(".miniclaude").resolve(".claude_trusted");
        return Files.exists(trustMarker);
    }

    // ==================== 工具实现 ====================

    /**
     * 执行 shell 命令。
     * <p>
     * 安全特性：
     * - 120 秒超时自动终止
     * - 输出截断到 50000 字符
     * - OS 自适应：Windows 用 cmd /c，Unix 用 bash -c
     * <p>
     * 注意：S07 的危险命令拦截由 BashSecurityValidator 和权限管线负责，
     * 这里只做基础的命令执行。
     * <p>
     * 对应 Python 原版：run_bash(command) 函数。
     *
     * @param command 要执行的 shell 命令
     * @return 命令输出（stdout + stderr 合并）
     */
    private static String runBash(String command) {
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

    // ==================== Agent 核心循环（权限感知） ====================

    /**
     * 权限感知的 Agent 核心循环：LLM 调用 → 权限检查 → 工具执行 → 结果回传。
     * <p>
     * 与 S02 的 agentLoop 相比，核心循环本身没有变化，
     * 只是在工具执行前增加了一层权限管线检查：
     * <pre>
     *   while (stopReason == TOOL_USE) {
     *       response = LLM(messages, tools);
     *       for each tool_use block:
     *           decision = permissionPipeline.check(toolName, toolInput);
     *           if (deny)    → return rejection message to LLM
     *           if (ask)     → ask user, execute if approved
     *           if (allow)   → execute tool
     *       append results;
     *   }
     * </pre>
     * <p>
     * 对应 Python 原版：agent_loop(messages, perms) 函数。
     *
     * @param client        Anthropic API 客户端
     * @param model         模型 ID
     * @param paramsBuilder 消息创建参数构建器（包含已有对话历史）
     * @param tools         工具定义列表（发送给 LLM）
     * @param toolHandlers  工具分发表：工具名 → 处理函数
     * @param perms         权限管理器实例
     * @param bashValidator Bash 安全校验器实例
     */
    @SuppressWarnings("unchecked")
    private static void agentLoop(AnthropicClient client, String model,
                                  MessageCreateParams.Builder paramsBuilder,
                                  List<Tool> tools,
                                  Map<String, Function<Map<String, Object>, String>> toolHandlers,
                                  PermissionManager perms,
                                  BashSecurityValidator bashValidator) {
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

            // ---- 4. 遍历 content blocks，执行工具调用（经权限检查） ----
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    String toolName = toolUse.name();

                    // 从 JsonValue 提取输入参数（转换为 Map<String, Object>）
                    Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());
                    if (input == null) input = Map.of();

                    // ---- 权限管线检查 ----
                    Map<String, String> decision = perms.check(toolName, input, bashValidator);
                    String behavior = decision.get("behavior");
                    String reason = decision.get("reason");
                    String output;

                    if ("deny".equals(behavior)) {
                        // 拒绝：直接返回拒绝消息给 LLM
                        output = "Permission denied: " + reason;
                        System.out.println(red("  [DENIED] " + toolName + ": " + reason));

                    } else if ("ask".equals(behavior)) {
                        // 询问用户：交互式审批
                        if (perms.askUser(toolName, input)) {
                            // 用户批准，执行工具
                            Function<Map<String, Object>, String> handler = toolHandlers.get(toolName);
                            output = handler != null ? handler.apply(input) : "Unknown tool: " + toolName;
                            System.out.println(bold("> " + toolName) + ":");
                            System.out.println(dim("  " + output.substring(0, Math.min(output.length(), 200))));
                        } else {
                            // 用户拒绝
                            output = "Permission denied by user for " + toolName;
                            System.out.println(red("  [USER DENIED] " + toolName));
                        }

                    } else {
                        // 允许：直接执行工具
                        Function<Map<String, Object>, String> handler = toolHandlers.get(toolName);
                        output = handler != null ? handler.apply(input) : "Unknown tool: " + toolName;
                        System.out.println(bold("> " + toolName) + ":");
                        System.out.println(dim("  " + output.substring(0, Math.min(output.length(), 200))));
                    }

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
     * REPL 主循环：选择权限模式 → 读取用户输入 → 权限感知 Agent 循环 → 打印结果。
     * <p>
     * 整体流程：
     * <pre>
     * 1. 启动时选择权限模式（default / plan / auto）
     * 2. while True:
     *     query = input("s07 >> ")       # 读取用户输入
     *     处理 REPL 命令（/mode、/rules）
     *     messages.append(query)         # 追加到历史
     *     agent_loop(messages, perms)    # 执行权限感知 Agent 循环
     * </pre>
     * <p>
     * REPL 命令：
     * - /mode &lt;mode&gt;：运行时切换权限模式
     * - /rules：显示当前权限规则列表
     */
    public static void main(String[] args) {
        // ---- 构建客户端和加载模型 ----
        AnthropicClient client = buildClient();
        String model = loadModel();

        // ---- 创建 Bash 安全校验器 ----
        BashSecurityValidator bashValidator = new BashSecurityValidator();

        // ---- 启动时选择权限模式 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println("Permission modes: default, plan, auto");
        System.out.print("Mode (default): ");
        String modeInput = "";
        if (scanner.hasNextLine()) {
            modeInput = scanner.nextLine().trim().toLowerCase();
        }
        if (modeInput.isEmpty() || !MODES.contains(modeInput)) {
            modeInput = "default";
        }

        // ---- 创建权限管理器 ----
        PermissionManager perms = new PermissionManager(modeInput, null);
        System.out.println(green("[Permission mode: " + modeInput + "]"));

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
        System.out.println(bold("S07 Permission System") + " — 4 tools with permission pipeline");
        System.out.println("Commands: /mode <mode>, /rules | Type 'q' or 'exit' to quit.\n");

        while (true) {
            // 打印提示符（青色 "s07 >>"）
            System.out.print(cyan("s07 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();

            // 空输入或退出命令 → 结束
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // /mode 命令：运行时切换权限模式
            if (query.startsWith("/mode")) {
                String[] parts = query.split("\\s+");
                if (parts.length == 2 && MODES.contains(parts[1])) {
                    perms.mode = parts[1];
                    System.out.println(green("[Switched to " + parts[1] + " mode]"));
                } else {
                    System.out.println("Usage: /mode <" + String.join("|", MODES) + ">");
                }
                continue;
            }

            // /rules 命令：显示当前权限规则列表
            if ("/rules".equals(query)) {
                for (int i = 0; i < perms.rules.size(); i++) {
                    System.out.println("  " + i + ": " + perms.rules.get(i));
                }
                continue;
            }

            // 追加用户消息到对话历史
            paramsBuilder.addUserMessage(query);

            // 执行权限感知的 Agent 循环（LLM 调用 + 权限检查 + 工具执行）
            try {
                agentLoop(client, model, paramsBuilder, tools, toolHandlers, perms, bashValidator);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }
            System.out.println(); // 每轮结束后空一行，视觉分隔
        }

        System.out.println(dim("Bye!"));
    }
}
