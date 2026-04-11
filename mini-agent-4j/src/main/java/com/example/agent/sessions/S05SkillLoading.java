package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvBuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S05：技能加载 —— 按需加载领域知识，不撑爆系统提示词（完全自包含）。
 * <p>
 * 两层注入模型：
 * - Layer 1（廉价）：技能名称 + 短描述注入 system prompt（~100 tokens/skill）
 * - Layer 2（按需）：模型调用 load_skill 工具时，完整技能正文通过 tool_result 返回
 * <p>
 * 关键洞察："别把所有东西塞进系统提示词。按需加载。"
 * <p>
 * 本文件零外部依赖（不导入 com.example.agent.*），所有基础设施内联：
 * buildClient(), loadModel(), defineTool(), safePath(),
 * runBash(), runRead(), runWrite(), runEdit(),
 * agentLoop(), jsonValueToObject(), ANSI 辅助方法,
 * 以及完整的 SkillRegistry 内部类。
 * <p>
 * 对应 Python 原版：s05_skill_loading.py
 */
public class S05SkillLoading {

    // ==================== 环境变量加载 ====================

    /** 全局 dotenv 实例：优先读 .env 文件，回退到系统环境变量 */
    private static final Dotenv DOTENV = new DotenvBuilder()
            .ignoreIfMissing()
            .systemProperties()
            .load();

    // ==================== ANSI 颜色辅助 ====================

    /** 返回青色（cyan）文本 */
    private static String cyan(String text) {
        return "\033[36m" + text + "\033[0m";
    }

    /** 返回灰色（dim）文本 */
    private static String dim(String text) {
        return "\033[2m" + text + "\033[0m";
    }

    // ==================== LLM 基础设施 ====================

    /** 工作目录 */
    private static final Path WORKDIR = Path.of(System.getProperty("user.dir"));

    /** 技能目录 */
    private static final Path SKILLS_DIR = WORKDIR.resolve("skills");

    /** Anthropic 客户端（线程安全） */
    private static final AnthropicClient CLIENT = buildClient();

    /** 模型 ID */
    private static final String MODEL = loadModel();

    /**
     * 构建 Anthropic 客户端。
     * 支持自定义 baseUrl（用于第三方 API 兼容端点）。
     * 如果设置了 ANTHROPIC_BASE_URL，则清除 ANTHROPIC_AUTH_TOKEN 以避免冲突。
     */
    private static AnthropicClient buildClient() {
        String baseUrl = DOTENV.get("ANTHROPIC_BASE_URL");

        // 如果使用自定义端点，清除可能的 auth token 冲突
        // Python 版用 os.environ.pop("ANTHROPIC_AUTH_TOKEN", None)
        // Java 中我们只是不使用它即可

        String apiKey = DOTENV.get("ANTHROPIC_API_KEY");
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
     * 加载模型 ID。
     */
    private static String loadModel() {
        String model = DOTENV.get("MODEL_ID");
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "MODEL_ID 未配置。请在 .env 文件或系统环境变量中设置。");
        }
        return model;
    }

    // ==================== 工具定义 ====================

    /**
     * 便捷方法：构建一个 Tool 定义。
     * 封装 Anthropic SDK 的 Tool.builder() 调用。
     *
     * @param name        工具名称
     * @param description 工具描述
     * @param properties  输入 schema 的 properties（key → 属性定义 Map）
     * @param required    必填参数列表，null 或空列表表示无必填参数
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
     * 安全路径解析：确保路径不逃逸工作目录。
     * 将相对路径解析为绝对路径，并验证它在 WORKDIR 内。
     *
     * @param pathStr 相对或绝对路径字符串
     * @return 解析后的安全绝对路径
     * @throws IllegalArgumentException 如果路径逃逸工作目录
     */
    private static Path safePath(String pathStr) {
        Path resolved = WORKDIR.resolve(pathStr).normalize().toAbsolutePath();
        if (!resolved.startsWith(WORKDIR.normalize().toAbsolutePath())) {
            throw new IllegalArgumentException("Path escapes workspace: " + pathStr);
        }
        return resolved;
    }

    // ==================== 工具实现 ====================

    /**
     * 执行 bash 命令。
     * 包含危险命令检查（rm -rf /, sudo, shutdown, reboot, > /dev/）。
     * 超时 120 秒，输出截断到 50000 字符。
     */
    private static String runBash(String command) {
        // 危险命令黑名单检查
        String[] dangerous = {"rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"};
        for (String item : dangerous) {
            if (command.contains(item)) {
                return "Error: Dangerous command blocked";
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(WORKDIR.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (120s)";
            }

            output = output.trim();
            if (output.isEmpty()) return "(no output)";
            return output.length() > 50000 ? output.substring(0, 50000) : output;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 读取文件内容。
     * 支持行数限制（limit），超出部分用 "... (N more lines)" 表示。
     * 输出截断到 50000 字符。
     */
    private static String runRead(String path, Integer limit) {
        try {
            Path filePath = safePath(path);
            List<String> lines = Files.readAllLines(filePath);

            // 行数限制
            if (limit != null && limit > 0 && limit < lines.size()) {
                int remaining = lines.size() - limit;
                lines = new ArrayList<>(lines.subList(0, limit));
                lines.add("... (" + remaining + " more lines)");
            }

            String content = String.join("\n", lines);
            return content.length() > 50000 ? content.substring(0, 50000) : content;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 写入文件内容。
     * 自动创建父目录。返回写入字节数。
     */
    private static String runWrite(String path, String content) {
        try {
            Path filePath = safePath(path);
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, content);
            return "Wrote " + content.length() + " bytes to " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 编辑文件：精确替换文本（仅替换第一次出现）。
     * 如果 old_text 不在文件中，返回错误。
     */
    private static String runEdit(String path, String oldText, String newText) {
        try {
            Path filePath = safePath(path);
            String content = Files.readString(filePath);

            if (!content.contains(oldText)) {
                return "Error: Text not found in " + path;
            }

            // 仅替换第一次出现
            String newContent = content.replaceFirst(
                    Pattern.quote(oldText), Matcher.quoteReplacement(newText));
            Files.writeString(filePath, newContent);
            return "Edited " + path;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    // ==================== SkillRegistry 内部类 ====================

    /**
     * 技能注册表：扫描 skills 目录，解析 SKILL.md 的 YAML frontmatter。
     * <p>
     * 技能目录结构：
     * <pre>
     * skills/
     *   pdf/
     *     SKILL.md          &lt;-- YAML frontmatter (name, description) + body
     *   code-review/
     *     SKILL.md
     * </pre>
     * <p>
     * YAML frontmatter 格式：
     * <pre>
     * ---
     * name: pdf
     * description: "处理 PDF 文档"
     * ---
     * 技能正文内容...
     * </pre>
     * <p>
     * 支持的 YAML 特性：
     * - 简单 key: value 对
     * - 块标量 key: | (保留换行) 和 key: &gt; (折叠换行)
     * - 缩进续行（以空格或 tab 开头的行追加到上一个 key）
     */
    static class SkillRegistry {

        /** YAML frontmatter 正则：--- 开头，--- 结尾，后跟正文（兼容 CRLF / LF） */
        private static final Pattern FRONTMATTER_PATTERN =
                Pattern.compile("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n(.*)", Pattern.DOTALL);

        /**
         * 技能清单数据：存储解析后的每个技能的元信息和正文。
         * key = 技能名, value = {name, description, body, path}
         */
        private final Map<String, Map<String, String>> documents = new LinkedHashMap<>();

        /**
         * 构造函数：扫描技能目录并加载所有 SKILL.md 文件。
         *
         * @param skillsDir 技能目录路径（如 project/skills）
         */
        SkillRegistry(Path skillsDir) {
            if (skillsDir == null || !Files.exists(skillsDir)) {
                return;
            }
            try (var stream = Files.walk(skillsDir)) {
                stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                        .sorted()
                        .forEach(this::loadSkillFile);
            } catch (IOException e) {
                // 技能目录扫描失败时静默忽略
            }
        }

        /**
         * 加载并解析单个 SKILL.md 文件。
         * 提取 frontmatter 中的 name 和 description，以及正文 body。
         */
        private void loadSkillFile(Path file) {
            try {
                String text = Files.readString(file);
                var parsed = parseFrontmatter(text);

                @SuppressWarnings("unchecked")
                Map<String, String> meta = (Map<String, String>) parsed.get("meta");
                String body = (String) parsed.get("body");

                // 技能名优先取 frontmatter 中的 name，否则取父目录名
                String name = meta.getOrDefault("name", file.getParent().getFileName().toString());
                String description = meta.getOrDefault("description", "No description");

                var doc = new LinkedHashMap<String, String>();
                doc.put("name", name);
                doc.put("description", description);
                doc.put("body", body);
                doc.put("path", file.toString());
                documents.put(name, doc);
            } catch (IOException e) {
                // 单个技能文件加载失败时静默忽略
            }
        }

        /**
         * 解析 YAML frontmatter。
         * <p>
         * 支持：简单 key: value、块标量 key: | (保留换行)、key: &gt; (折叠换行)、缩进续行。
         * 不引入完整 YAML 解析器，覆盖 SKILL.md 中常见的写法。
         *
         * @param text SKILL.md 文件的完整文本
         * @return Map 包含 "meta" (Map&lt;String, String&gt;) 和 "body" (String)
         */
        private Map<String, Object> parseFrontmatter(String text) {
            Matcher match = FRONTMATTER_PATTERN.matcher(text);
            if (!match.matches()) {
                return Map.of("meta", Map.of(), "body", text);
            }

            String frontmatter = match.group(1);
            String[] lines = frontmatter.split("\\r?\\n");

            Map<String, String> meta = new LinkedHashMap<>();
            int i = 0;
            while (i < lines.length) {
                String line = lines[i];

                // 缩进行属于上一个 key 的续行，跳过（已在下面消费）
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    i++;
                    continue;
                }

                int colon = line.indexOf(':');
                if (colon <= 0) {
                    i++;
                    continue;
                }

                String key = line.substring(0, colon).strip();
                String rest = line.substring(colon + 1).strip();

                // 块标量 key: | 或 key: >
                if (rest.equals("|") || rest.equals(">")) {
                    boolean literal = rest.equals("|");
                    StringBuilder sb = new StringBuilder();
                    i++;
                    while (i < lines.length) {
                        String cont = lines[i];
                        // 块标量结束条件：非空行且没有缩进
                        if (!cont.isEmpty() && !cont.startsWith(" ") && !cont.startsWith("\t")) {
                            break;
                        }
                        // 去掉公共缩进（至少一个空格/tab）
                        if (!cont.isEmpty()) {
                            cont = cont.replaceFirst("^[ \\t]{1,}", "");
                        }
                        sb.append(cont).append(literal ? "\n" : " ");
                        i++;
                    }
                    String value = literal ? sb.toString().stripTrailing() : sb.toString().strip();
                    meta.put(key, value);
                    continue;
                }

                // 简单 key: value（可能跨行续行，如 description 那样的多行文本）
                StringBuilder valueSb = new StringBuilder(rest);
                i++;
                while (i < lines.length) {
                    String cont = lines[i];
                    if (cont.isEmpty() || (!cont.startsWith(" ") && !cont.startsWith("\t"))) {
                        break;
                    }
                    valueSb.append(" ").append(cont.strip());
                    i++;
                }
                meta.put(key, valueSb.toString().strip());
            }

            return Map.of("meta", meta, "body", match.group(2).strip());
        }

        /**
         * Layer 1：获取所有技能的简短描述（注入 system prompt）。
         * 每行格式：- 技能名: 简短描述
         * 如果没有技能，返回 "(no skills available)"。
         *
         * @return 格式化的技能描述字符串
         */
        String describeAvailable() {
            if (documents.isEmpty()) {
                return "(no skills available)";
            }

            var lines = new ArrayList<String>();
            for (var entry : documents.entrySet()) {
                String name = entry.getKey();
                String desc = entry.getValue().getOrDefault("description", "No description");
                lines.add("- " + name + ": " + desc);
            }
            return String.join("\n", lines);
        }

        /**
         * Layer 2：获取完整技能内容（通过 tool_result 返回）。
         * 返回格式：&lt;skill name="..."&gt;\n正文\n&lt;/skill&gt;
         * 如果技能不存在，返回错误提示和可用技能列表。
         *
         * @param name 技能名称
         * @return 格式化的技能完整内容，或错误信息
         */
        String loadFullText(String name) {
            var doc = documents.get(name);
            if (doc == null) {
                String known = documents.isEmpty()
                        ? "(none)"
                        : String.join(", ", documents.keySet());
                return "Error: Unknown skill '" + name + "'. Available skills: " + known;
            }

            return "<skill name=\"" + doc.get("name") + "\">\n"
                    + doc.get("body") + "\n"
                    + "</skill>";
        }
    }

    // ==================== 全局 SkillRegistry ====================

    /** 技能注册表：启动时扫描 skills 目录并加载所有 SKILL.md */
    private static final SkillRegistry SKILL_REGISTRY = new SkillRegistry(SKILLS_DIR);

    // ==================== 系统提示词 ====================

    /** 系统提示词：包含技能目录（Layer 1） */
    private static final String SYSTEM = "You are a coding agent at " + WORKDIR + ".\n"
            + "Use load_skill when a task needs specialized instructions before you act.\n\n"
            + "Skills available:\n"
            + SKILL_REGISTRY.describeAvailable();

    // ==================== 工具定义列表 ====================

    /** 5 个工具：bash, read_file, write_file, edit_file, load_skill */
    private static final List<Tool> TOOLS = List.of(
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
            // edit_file：精确替换文件中的文本
            defineTool("edit_file", "Replace exact text in a file once.",
                    Map.of(
                            "path", Map.of("type", "string"),
                            "old_text", Map.of("type", "string"),
                            "new_text", Map.of("type", "string")),
                    List.of("path", "old_text", "new_text")),
            // load_skill：按需加载技能（Layer 2）
            defineTool("load_skill", "Load the full body of a named skill into the current context.",
                    Map.of("name", Map.of("type", "string")),
                    List.of("name"))
    );

    // ==================== 工具分发 ====================

    /**
     * 工具处理器映射：工具名 → 处理函数。
     * 每个处理器接收 Map&lt;String, Object&gt; 输入参数，返回 String 输出。
     */
    private static final Map<String, java.util.function.Function<Map<String, Object>, String>> TOOL_HANDLERS =
            new LinkedHashMap<>();

    static {
        TOOL_HANDLERS.put("bash", input -> runBash((String) input.get("command")));
        TOOL_HANDLERS.put("read_file", input -> {
            String path = (String) input.get("path");
            Integer limit = input.get("limit") instanceof Number n ? n.intValue() : null;
            return runRead(path, limit);
        });
        TOOL_HANDLERS.put("write_file", input ->
                runWrite((String) input.get("path"), (String) input.get("content")));
        TOOL_HANDLERS.put("edit_file", input ->
                runEdit((String) input.get("path"),
                        (String) input.get("old_text"),
                        (String) input.get("new_text")));
        // Layer 2：load_skill 返回完整技能内容
        TOOL_HANDLERS.put("load_skill", input ->
                SKILL_REGISTRY.loadFullText((String) input.get("name")));
    }

    // ==================== Agent 循环 ====================

    /**
     * Agent 核心循环：LLM 调用 → 工具执行 → 结果回传。
     * <p>
     * 核心模式极其简单：
     * <pre>
     *   while (stopReason == TOOL_USE) {
     *       response = LLM(messages, tools);
     *       execute tools;
     *       append results;
     *   }
     * </pre>
     * <p>
     * 使用 Anthropic SDK 的 MessageCreateParams.Builder 累积对话历史，
     * 通过 addMessage(response) 直接追加 LLM 的响应对象。
     *
     * @param paramsBuilder 消息创建参数构建器（包含已有对话历史）
     */
    private static void agentLoop(MessageCreateParams.Builder paramsBuilder) {
        while (true) {
            // ---- 1. 调用 LLM ----
            Message response = CLIENT.messages().create(paramsBuilder.build());

            // ---- 2. 将 assistant 回复追加到历史 ----
            paramsBuilder.addMessage(response);

            // ---- 3. 检查是否需要继续执行工具 ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                // 模型决定停止，打印文本回复
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(tb -> System.out.println(tb.text()));
                }
                return;
            }

            // ---- 4. 遍历 content blocks，执行工具调用 ----
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (!block.isToolUse()) continue;

                ToolUseBlock toolUse = block.asToolUse();
                String toolName = toolUse.name();

                // 从 JsonValue 提取输入参数
                @SuppressWarnings("unchecked")
                Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());

                // 执行工具
                String output;
                var handler = TOOL_HANDLERS.get(toolName);
                try {
                    output = handler != null
                            ? handler.apply(input != null ? input : Map.of())
                            : "Unknown tool: " + toolName;
                } catch (Exception e) {
                    output = "Error: " + e.getMessage();
                }

                // 打印工具执行摘要（截断到 200 字符）
                System.out.println("> " + toolName + ": "
                        + output.substring(0, Math.min(200, output.length())));

                // 输出截断到 50000 字符
                if (output.length() > 50000) {
                    output = output.substring(0, 50000);
                }

                // 构造 tool_result
                toolResults.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(toolUse.id())
                                .content(output)
                                .build()));
            }

            // ---- 5. 将工具结果追加为 user 消息 ----
            paramsBuilder.addUserMessageOfBlockParams(toolResults);
        }
    }

    // ==================== JsonValue 转换 ====================

    /**
     * 将 Anthropic SDK 的 JsonValue 转换为普通 Java 对象。
     * 支持递归转换嵌套的 Map 和 List。
     * 返回类型可能是：String, Number, Boolean, Map&lt;String, Object&gt;, List&lt;Object&gt;, 或 null。
     *
     * @param value Anthropic SDK 的 JsonValue 对象
     * @return 转换后的普通 Java 对象
     */
    @SuppressWarnings("unchecked")
    private static Object jsonValueToObject(JsonValue value) {
        if (value == null) return null;

        // 尝试作为 String（优先检查，因为最常见）
        var strOpt = value.asString();
        if (strOpt.isPresent()) {
            return strOpt.get();
        }

        // 尝试作为 Number
        var numOpt = value.asNumber();
        if (numOpt.isPresent()) {
            return numOpt.get();
        }

        // 尝试作为 Boolean
        var boolOpt = value.asBoolean();
        if (boolOpt.isPresent()) {
            return boolOpt.get();
        }

        // 尝试作为 Map（Object）
        try {
            var mapOpt = value.asObject();
            if (mapOpt.isPresent()) {
                Map<String, JsonValue> map = (Map<String, JsonValue>) (Object) mapOpt.get();
                var result = new LinkedHashMap<String, Object>();
                for (var entry : map.entrySet()) {
                    result.put(entry.getKey(), jsonValueToObject(entry.getValue()));
                }
                return result;
            }
        } catch (ClassCastException ignored) {
        }

        // 尝试作为 List（Array）
        try {
            var listOpt = value.asArray();
            if (listOpt.isPresent()) {
                List<JsonValue> list = (List<JsonValue>) (Object) listOpt.get();
                var result = new ArrayList<Object>();
                for (var item : list) {
                    result.add(jsonValueToObject(item));
                }
                return result;
            }
        } catch (ClassCastException ignored) {
        }

        return null;
    }

    // ==================== 主入口 ====================

    /**
     * 主入口：REPL 循环。
     * <p>
     * 用户输入查询 → 追加到 paramsBuilder → 调用 agentLoop → 打印最终回复。
     * 输入 "q", "exit", 或空行退出。
     * <p>
     * 提示符样式：青色 "s05 >>"
     */
    public static void main(String[] args) {
        // 打印启动信息
        System.out.println(dim("S05 Skill Loading | skills dir: " + SKILLS_DIR));
        System.out.println(dim("Loaded skills: " + (SKILL_REGISTRY.documents.isEmpty()
                ? "(none)" : String.join(", ", SKILL_REGISTRY.documents.keySet()))));

        // 构建 LLM 参数（含 system prompt 和工具定义）
        var paramsBuilder = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(8000)
                .system(SYSTEM);
        for (Tool tool : TOOLS) {
            paramsBuilder.addTool(tool);
        }

        // REPL 主循环
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print(cyan("s05 >> "));
            System.out.flush();

            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();

            // 退出条件
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // 追加用户消息
            paramsBuilder.addUserMessage(query);

            // 调用 agent 循环
            try {
                agentLoop(paramsBuilder);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }

            System.out.println();
        }
    }
}
