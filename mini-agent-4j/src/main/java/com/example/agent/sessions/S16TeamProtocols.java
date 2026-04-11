package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvBuilder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * S16：团队协议 —— 完全自包含实现（不依赖 core/、tools/、util/、team/ 包）。
 * <p>
 * 在 S15（Agent 团队）基础上增加两种握手协议，使用 request_id 关联请求与响应。
 * <p>
 * <b>协议 1：Shutdown（关闭握手）</b>
 * <pre>
 *     Lead ──shutdown_request(req_id)──→ Teammate
 *     Teammate 处理并更新 tracker
 *     Lead 用 shutdown_response(req_id) 检查状态
 * </pre>
 * <p>
 * <b>协议 2：Plan Approval（计划审批）</b>
 * <pre>
 *     Teammate ──plan_approval(req_id, plan)──→ Lead
 *     Lead 审查后用 plan_approval(req_id, approve, feedback) 回复
 * </pre>
 * <p>
 * 关键洞察："协议 = 消息类型 + request_id 关联 + 状态机跟踪。"
 * <p>
 * 本文件将所有基础设施内联：
 * <ul>
 *   <li>buildClient()：构建 Anthropic API 客户端</li>
 *   <li>loadModel()：从环境变量加载模型 ID</li>
 *   <li>defineTool()：构建 SDK Tool 定义</li>
 *   <li>runBash() / runRead() / runWrite() / runEdit()：四个基础工具实现</li>
 *   <li>safePath()：路径沙箱校验</li>
 *   <li>jsonValueToObject()：JsonValue → 普通 Java 对象转换</li>
 *   <li>MessageBus：基于文件系统的消息总线（send / readInbox / broadcast）</li>
 *   <li>TeammateManager：队友生命周期管理（spawn / teammateLoop / listAll / memberNames）</li>
 * </ul>
 * <p>
 * REPL 提示符：{@code s16 >> }
 * <p>
 * Lead 工具（12 个）：bash, read_file, write_file, edit_file, spawn_teammate,
 * list_teammates, send_message, read_inbox, broadcast,
 * shutdown_request, shutdown_response, plan_approval
 * <p>
 * Teammate 工具（8 个）：bash, read_file, write_file, edit_file,
 * send_message, read_inbox, shutdown_response, plan_approval
 * <p>
 * 对应 Python 原版：s16_team_protocols.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s16_team_protocols.py">Python 原版</a>
 */
public class S16TeamProtocols {

    // ==================== 常量 ====================

    /** 最大输出长度（字符），与 Python 原版 50000 对齐 */
    private static final int MAX_OUTPUT = 50000;

    /** bash 命令超时（秒） */
    private static final int BASH_TIMEOUT = 120;

    /** 工具结果预览打印长度 */
    private static final int PREVIEW_LEN = 200;

    /** 危险命令黑名单，防止 Agent 执行破坏性操作 */
    private static final List<String> DANGEROUS_COMMANDS = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    /** 工作目录（Agent 的文件操作沙箱根目录） */
    private static final Path WORK_DIR = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();

    /** JSON 序列化/反序列化工具（用于消息和团队配置的读写） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Teammate 工作循环最大轮次（防止无限循环） */
    private static final int MAX_TEAMMATE_ROUNDS = 50;

    /** Teammate 最大输出 token 数 */
    private static final long TEAMMATE_MAX_TOKENS = 8000;

    // ==================== 协议状态追踪器 ====================

    /**
     * Shutdown 请求追踪器。
     * key = request_id, value = {target, status}
     * status 取值：pending / approved / rejected
     */
    private static final ConcurrentHashMap<String, Map<String, Object>> shutdownRequests =
            new ConcurrentHashMap<>();

    /**
     * Plan Approval 请求追踪器。
     * key = request_id, value = {from, plan, status}
     * status 取值：pending / approved / rejected
     */
    private static final ConcurrentHashMap<String, Map<String, Object>> planRequests =
            new ConcurrentHashMap<>();

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
     * 从项目根目录加载 .env 配置文件，ignoreIfMissing() 确保文件不存在时不报错。
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
     * 支持自定义 baseUrl（用于第三方 API 兼容端点，如 OpenRouter 等）。
     * 如果设置了 ANTHROPIC_BASE_URL，则清除 ANTHROPIC_AUTH_TOKEN 避免认证冲突。
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
     * 工具定义描述了工具的名称、用途和输入参数格式（JSON Schema）。
     * LLM 根据这些信息决定何时调用哪个工具、传什么参数。
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
     * normalize() 处理 .. 和 . 路径段，startsWith() 确保结果路径仍在沙箱内。
     *
     * @param relativePath 相对路径（或绝对路径）
     * @return 校验后的安全绝对路径
     * @throws SecurityException 如果路径试图逃逸工作目录
     */
    private static Path safePath(String relativePath) {
        Path resolved = WORK_DIR.resolve(relativePath).normalize().toAbsolutePath();
        if (!resolved.startsWith(WORK_DIR)) {
            throw new SecurityException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }

    // ==================== 基础工具实现 ====================

    /**
     * 执行 shell 命令。
     * <p>
     * 安全特性：
     * <ul>
     *   <li>危险命令黑名单检查（rm -rf /、sudo、shutdown 等）</li>
     *   <li>超时限制（120 秒）</li>
     *   <li>输出截断到 50000 字符</li>
     *   <li>OS 自适应：Windows 用 cmd /c，其他用 bash -c</li>
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

            boolean finished = process.waitFor(BASH_TIMEOUT, java.util.concurrent.TimeUnit.SECONDS);
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
     * 支持可选的行数限制，超出部分用 "... (N more lines)" 提示。
     *
     * @param path  文件路径（相对或绝对）
     * @param limit 可选的最大读取行数
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
     * 自动创建父目录（如果不存在）。
     *
     * @param path    文件路径
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
     * 精确文本替换（仅替换第一次出现）。
     * <p>
     * 使用 Pattern.quote() 确保搜索文本被当作字面量处理，
     * 不会误解析为正则表达式。
     *
     * @param path    文件路径
     * @param oldText 要查找的原始文本
     * @param newText 替换后的新文本
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
     * 转换规则：
     * <ul>
     *   <li>JsonValue(String) → Java String</li>
     *   <li>JsonValue(Number) → Java Number</li>
     *   <li>JsonValue(Boolean) → Java Boolean</li>
     *   <li>JsonValue(Object) → LinkedHashMap</li>
     *   <li>JsonValue(Array) → ArrayList</li>
     * </ul>
     *
     * @param value SDK 的 JsonValue 实例
     * @return 转换后的普通 Java 对象
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

    // ==================== MessageBus 内部类 ====================

    /**
     * 基于文件系统的消息总线。
     * <p>
     * 每个成员拥有一个收件箱文件（.team/inbox/{name}.json）。
     * 消息以 JSON 数组形式追加，读取时排空（drain）。
     * <p>
     * 三种操作：
     * <ul>
     *   <li>send(from, to, content, msgType, metadata) — 发送单条消息</li>
     *   <li>readInbox(name) — 读取并排空收件箱</li>
     *   <li>broadcast(from, content, recipients) — 群发给多个收件人</li>
     * </ul>
     * <p>
     * 对应 Python 原版：MessageBus 类。
     */
    public static class MessageBus {

        /** 收件箱目录路径 */
        private final Path inboxDir;

        /**
         * 构造消息总线。
         *
         * @param inboxDir 收件箱目录路径（通常为 .team/inbox）
         */
        public MessageBus(Path inboxDir) {
            this.inboxDir = inboxDir;
            try {
                Files.createDirectories(inboxDir);
            } catch (Exception ignored) {}
        }

        /**
         * 发送消息。
         * <p>
         * 消息结构：
         * <pre>{from, content, msg_type, metadata, timestamp}</pre>
         *
         * @param from      发送者名称
         * @param to        接收者名称
         * @param content   消息内容
         * @param msgType   消息类型（message / broadcast / shutdown_request / shutdown_response / plan_approval）
         * @param metadata  可选的附加元数据
         * @return 发送结果描述
         */
        public String send(String from, String to, String content,
                           String msgType, Map<String, Object> metadata) {
            try {
                Path inboxFile = inboxDir.resolve(to + ".json");
                List<Map<String, Object>> messages = new ArrayList<>();

                // 读取已有消息
                if (Files.exists(inboxFile)) {
                    String json = Files.readString(inboxFile);
                    if (!json.isBlank()) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> existing = MAPPER.readValue(json, List.class);
                        messages.addAll(existing);
                    }
                }

                // 构造新消息
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("from", from);
                msg.put("content", content);
                msg.put("msg_type", msgType != null ? msgType : "message");
                if (metadata != null) {
                    msg.put("metadata", metadata);
                }
                msg.put("timestamp", System.currentTimeMillis() / 1000.0);
                messages.add(msg);

                // 写入收件箱（覆盖写，因为 readInbox 会 drain）
                Files.writeString(inboxFile,
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(messages));
                return "Sent to " + to;
            } catch (Exception e) {
                return "Error sending message: " + e.getMessage();
            }
        }

        /**
         * 读取并排空收件箱。
         * <p>
         * 读取后立即清空收件箱文件，确保消息不会被重复消费。
         *
         * @param name 收件人名称
         * @return 消息列表（可能为空）
         */
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> readInbox(String name) {
            try {
                Path inboxFile = inboxDir.resolve(name + ".json");
                if (!Files.exists(inboxFile)) return Collections.emptyList();

                String json = Files.readString(inboxFile);
                if (json.isBlank()) return Collections.emptyList();

                List<Map<String, Object>> messages = MAPPER.readValue(json, List.class);

                // 排空：清空收件箱
                Files.writeString(inboxFile, "[]");

                return messages;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        /**
         * 广播消息给多个收件人。
         *
         * @param from       发送者名称
         * @param content    消息内容
         * @param recipients 收件人名称列表
         * @return 广播结果描述
         */
        public String broadcast(String from, String content, List<String> recipients) {
            for (String to : recipients) {
                send(from, to, content, "broadcast", null);
            }
            return "Broadcast to " + recipients.size() + " teammates";
        }
    }

    // ==================== TeammateManager 内部类 ====================

    /**
     * Teammate 生命周期管理器。
     * <p>
     * 职责：
     * <ul>
     *   <li>spawn(name, role, prompt) — 创建新队友（虚拟线程）</li>
     *   <li>teammateLoop(name, role, prompt) — 队友工作循环（含协议工具）</li>
     *   <li>listAll() — 列出所有队友及状态</li>
     *   <li>memberNames() — 获取所有队友名称列表</li>
     * </ul>
     * <p>
     * 团队配置持久化到 .team/config.json，包含：
     * <pre>{team_name, members: [{name, role, status}]}</pre>
     * <p>
     * 对应 Python 原版：TeammateManager 类。
     */
    public static class TeammateManager {

        /** 团队配置目录 */
        private final Path teamDir;

        /** 团队配置文件路径 */
        private final Path configPath;

        /** 团队配置数据（内存缓存） */
        private Map<String, Object> teamConfig;

        /** 消息总线（所有队友共享） */
        private final MessageBus bus;

        /** Anthropic API 客户端（供 teammate 直接 API 调用） */
        private final AnthropicClient client;

        /** 模型 ID（供 teammate 使用） */
        private final String model;

        /**
         * 构造 TeammateManager。
         *
         * @param teamDir 团队目录路径（通常为 .team/）
         * @param bus     消息总线实例
         * @param client  Anthropic API 客户端
         * @param model   模型 ID
         */
        @SuppressWarnings("unchecked")
        public TeammateManager(Path teamDir, MessageBus bus,
                               AnthropicClient client, String model) {
            this.teamDir = teamDir;
            this.configPath = teamDir.resolve("config.json");
            this.bus = bus;
            this.client = client;
            this.model = model;

            // 加载或初始化团队配置
            try {
                Files.createDirectories(teamDir);
            } catch (Exception ignored) {}

            if (Files.exists(configPath)) {
                try {
                    this.teamConfig = MAPPER.readValue(Files.readString(configPath), Map.class);
                } catch (Exception ignored) {}
            }

            if (this.teamConfig == null) {
                this.teamConfig = new LinkedHashMap<>();
                this.teamConfig.put("team_name", "default");
                this.teamConfig.put("members", new ArrayList<Map<String, Object>>());
            }
        }

        /**
         * 保存团队配置到磁盘。
         */
        private synchronized void saveTeamConfig() {
            try {
                Files.createDirectories(teamDir);
                Files.writeString(configPath,
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(teamConfig));
            } catch (Exception ignored) {}
        }

        /**
         * 查找指定名称的成员。
         *
         * @param name 成员名称
         * @return 成员配置 Map，不存在则返回 null
         */
        @SuppressWarnings("unchecked")
        private synchronized Map<String, Object> findMember(String name) {
            var members = (List<Map<String, Object>>) teamConfig.get("members");
            return members.stream()
                    .filter(m -> name.equals(m.get("name")))
                    .findFirst().orElse(null);
        }

        /**
         * 更新成员状态。
         *
         * @param name   成员名称
         * @param status 新状态（working / idle / shutdown）
         */
        private synchronized void setMemberStatus(String name, String status) {
            var member = findMember(name);
            if (member != null) {
                member.put("status", status);
                saveTeamConfig();
            }
        }

        /**
         * 列出所有队友及状态。
         *
         * @return 格式化的队友列表字符串
         */
        @SuppressWarnings("unchecked")
        public String listAll() {
            var members = (List<Map<String, Object>>) teamConfig.get("members");
            if (members.isEmpty()) return "No teammates.";
            var lines = new ArrayList<String>();
            lines.add("Team: " + teamConfig.get("team_name"));
            for (var m : members) {
                lines.add("  " + m.get("name") + " (" + m.get("role") + "): " + m.get("status"));
            }
            return String.join("\n", lines);
        }

        /**
         * 获取所有成员名称列表。
         *
         * @return 成员名称列表
         */
        @SuppressWarnings("unchecked")
        public List<String> memberNames() {
            var members = (List<Map<String, Object>>) teamConfig.get("members");
            return members.stream().map(m -> (String) m.get("name")).toList();
        }

        /**
         * 创建（spawn）一个新队友。
         * <p>
         * 如果同名队友已存在且状态为 idle/shutdown，则重新激活。
         * 否则拒绝创建，避免重复。
         *
         * @param name   队友名称（唯一标识）
         * @param role   队友角色描述
         * @param prompt 初始提示词（分配给队友的任务）
         * @return 操作结果描述
         */
        @SuppressWarnings("unchecked")
        public synchronized String spawn(String name, String role, String prompt) {
            var member = findMember(name);
            if (member != null) {
                String status = (String) member.get("status");
                if (!"idle".equals(status) && !"shutdown".equals(status)) {
                    return "Error: '" + name + "' is currently " + status;
                }
                // 重新激活已有成员
                member.put("status", "working");
                member.put("role", role);
            } else {
                // 创建新成员
                member = new LinkedHashMap<>(Map.of(
                        "name", name, "role", role, "status", "working"));
                ((List<Map<String, Object>>) teamConfig.get("members")).add(member);
            }
            saveTeamConfig();

            // 在虚拟线程中启动队友工作循环
            Thread.ofVirtual().name("agent-" + name)
                    .start(() -> teammateLoop(name, role, prompt));

            return "Spawned '" + name + "' (role: " + role + ")";
        }

        /**
         * Teammate 工作循环（自包含，8 个工具含协议）。
         * <p>
         * 队友拥有独立的 LLM 会话和工具集：
         * <ul>
         *   <li>6 个基础工具：bash, read_file, write_file, edit_file, send_message, read_inbox</li>
         *   <li>2 个协议工具：shutdown_response, plan_approval</li>
         * </ul>
         * <p>
         * 关键行为：
         * <ul>
         *   <li>shouldExit 标志：当 shutdown_response(approve=true) 后设为 true</li>
         *   <li>退出后读取完收件箱再结束，确保不丢失消息</li>
         *   <li>最终状态：shutdown（协议退出）或 idle（正常结束）</li>
         * </ul>
         * <p>
         * 对应 Python 原版：TeammateManager._teammate_loop()
         *
         * @param name   队友名称
         * @param role   队友角色
         * @param prompt 初始提示词
         */
        @SuppressWarnings("unchecked")
        private void teammateLoop(String name, String role, String prompt) {
            // 队友系统提示词：告知身份、职责和协议行为
            String sysPrompt = "You are '" + name + "', role: " + role + ", at " + WORK_DIR + ". "
                    + "Submit plans via plan_approval before major work. "
                    + "Respond to shutdown_request with shutdown_response.";

            // 构建消息参数
            var paramsBuilder = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(TEAMMATE_MAX_TOKENS)
                    .system(sysPrompt);

            // ---- Teammate 工具集（8 个） ----
            List<Tool> tools = List.of(
                    // 基础工具（6 个）
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
                                    "content", Map.of("type", "string"),
                                    "msg_type", Map.of("type", "string",
                                            "enum", List.of("message", "broadcast", "shutdown_request",
                                                    "shutdown_response", "plan_approval_response"))),
                            List.of("to", "content")),
                    defineTool("read_inbox", "Read and drain your inbox.",
                            Map.of(), null),
                    // 协议工具（2 个）
                    defineTool("shutdown_response",
                            "Respond to a shutdown request. Approve to shut down, reject to keep working.",
                            Map.of("request_id", Map.of("type", "string"),
                                    "approve", Map.of("type", "boolean"),
                                    "reason", Map.of("type", "string")),
                            List.of("request_id", "approve")),
                    defineTool("plan_approval",
                            "Submit a plan for lead approval. Provide plan text.",
                            Map.of("plan", Map.of("type", "string")),
                            List.of("plan"))
            );

            for (Tool tool : tools) {
                paramsBuilder.addTool(tool);
            }

            // 注入初始提示词作为第一条用户消息
            paramsBuilder.addUserMessage(prompt);

            // ---- Teammate 工具分发表 ----
            Map<String, Function<Map<String, Object>, String>> toolHandlers = new LinkedHashMap<>();

            // bash：执行 shell 命令
            toolHandlers.put("bash", input -> {
                String command = (String) input.get("command");
                if (command == null || command.isBlank()) return "Error: command is required";
                return runBash(command);
            });
            // read_file：读取文件
            toolHandlers.put("read_file", input -> {
                String path = (String) input.get("path");
                if (path == null || path.isBlank()) return "Error: path is required";
                Integer limit = null;
                Object limitObj = input.get("limit");
                if (limitObj instanceof Number num) limit = num.intValue();
                return runRead(path, limit);
            });
            // write_file：写入文件
            toolHandlers.put("write_file", input -> {
                String path = (String) input.get("path");
                String content = (String) input.get("content");
                if (path == null || path.isBlank()) return "Error: path is required";
                if (content == null) return "Error: content is required";
                return runWrite(path, content);
            });
            // edit_file：精确文本替换
            toolHandlers.put("edit_file", input -> {
                String path = (String) input.get("path");
                String oldText = (String) input.get("old_text");
                String newText = (String) input.get("new_text");
                if (path == null || path.isBlank()) return "Error: path is required";
                if (oldText == null) return "Error: old_text is required";
                if (newText == null) return "Error: new_text is required";
                return runEdit(path, oldText, newText);
            });
            // send_message：发送消息给其他队友或 lead
            toolHandlers.put("send_message", input -> {
                String to = (String) input.get("to");
                String content = (String) input.get("content");
                String msgType = (String) input.getOrDefault("msg_type", "message");
                if (to == null || to.isBlank()) return "Error: to is required";
                if (content == null) return "Error: content is required";
                return bus.send(name, to, content, msgType, null);
            });
            // read_inbox：读取并排空收件箱
            toolHandlers.put("read_inbox", input -> {
                try {
                    return MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(bus.readInbox(name));
                } catch (Exception e) {
                    return "[]";
                }
            });

            // ---- 协议工具分发 ----

            // shutdown_response：队友响应关闭请求
            // 更新 tracker 状态 + 发送响应消息给 lead
            toolHandlers.put("shutdown_response", input -> {
                String reqId = (String) input.get("request_id");
                boolean approve = Boolean.TRUE.equals(input.get("approve"));
                String reason = (String) input.getOrDefault("reason", "");

                // 更新 shutdown tracker 状态
                var req = shutdownRequests.get(reqId);
                if (req != null) {
                    req.put("status", approve ? "approved" : "rejected");
                }

                // 发送响应消息到 lead 的收件箱
                bus.send(name, "lead", reason, "shutdown_response",
                        Map.of("request_id", reqId, "approve", approve));

                return "Shutdown " + (approve ? "approved" : "rejected");
            });

            // plan_approval：队友提交计划等待审批
            // 创建 request_id + 发送到 lead 收件箱
            toolHandlers.put("plan_approval", input -> {
                String planText = (String) input.get("plan");
                String reqId = UUID.randomUUID().toString().substring(0, 8);

                // 记录到 plan tracker
                planRequests.put(reqId, new ConcurrentHashMap<>(Map.of(
                        "from", name, "plan", planText, "status", "pending")));

                // 发送到 lead 收件箱
                bus.send(name, "lead", planText, "plan_approval",
                        Map.of("request_id", reqId, "plan", planText));

                return "Plan submitted (request_id=" + reqId + "). Waiting for lead approval.";
            });

            // ---- 工作循环（shouldExit 追踪） ----
            // shouldExit 标志：当 shutdown_response(approve=true) 后设为 true
            // 下一轮读到 inbox 后再退出，确保不丢失消息
            boolean shouldExit = false;

            for (int round = 0; round < MAX_TEAMMATE_ROUNDS; round++) {
                // ---- 检查收件箱 ----
                // 每轮开始时先读取收件箱，将新消息注入对话
                var inbox = bus.readInbox(name);
                for (var msg : inbox) {
                    try {
                        paramsBuilder.addUserMessage(MAPPER.writeValueAsString(msg));
                    } catch (Exception ignored) {}
                }

                // 如果上一轮已批准 shutdown，本轮读到 inbox 后退出
                if (shouldExit) break;

                try {
                    // ---- 调用 LLM ----
                    Message response = client.messages().create(paramsBuilder.build());
                    paramsBuilder.addMessage(response);

                    // ---- 检查停止原因 ----
                    if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                        // 模型认为任务完成，退出循环
                        break;
                    }

                    // ---- 执行工具调用 ----
                    List<ContentBlockParam> results = new ArrayList<>();
                    for (ContentBlock block : response.content()) {
                        if (block.isToolUse()) {
                            ToolUseBlock toolUse = block.asToolUse();

                            // 提取工具输入参数
                            Map<String, Object> toolInput = (Map<String, Object>)
                                    jsonValueToObject(toolUse._input());
                            if (toolInput == null) toolInput = Map.of();

                            // 分发执行
                            String toolName = toolUse.name();
                            Function<Map<String, Object>, String> handler = toolHandlers.get(toolName);
                            String output;
                            if (handler != null) {
                                output = handler.apply(toolInput);
                            } else {
                                output = "Unknown tool: " + toolName;
                            }

                            // 打印工具执行日志（dim 颜色）
                            System.out.println(dim("  [" + name + "] "
                                    + toolName + ": "
                                    + output.substring(0, Math.min(output.length(), PREVIEW_LEN))));

                            // 构造工具结果 block
                            results.add(ContentBlockParam.ofToolResult(
                                    ToolResultBlockParam.builder()
                                            .toolUseId(toolUse.id())
                                            .content(output)
                                            .build()));

                            // 检查是否批准了 shutdown —— 设置 shouldExit 标志
                            if ("shutdown_response".equals(toolName)
                                    && Boolean.TRUE.equals(toolInput.get("approve"))) {
                                shouldExit = true;
                            }
                        }
                    }
                    // 将工具结果追加为 user 消息
                    paramsBuilder.addUserMessageOfBlockParams(results);

                } catch (Exception e) {
                    System.out.println(red("  [" + name + "] Error: " + e.getMessage()));
                    break;
                }
            }

            // 更新最终状态：shutdown（协议退出）或 idle（正常结束）
            setMemberStatus(name, shouldExit ? "shutdown" : "idle");
            System.out.println(dim("  [" + name + "] Exited (" + (shouldExit ? "shutdown" : "idle") + ")"));
        }
    }

    // ==================== Lead 协议处理器 ====================

    /**
     * 发起 shutdown 请求。
     * <p>
     * 生成唯一的 request_id，记录到 tracker，发送 shutdown_request 消息到目标队友。
     * <p>
     * 对应 Python 原版：handle_shutdown_request(teammate)
     *
     * @param teammate   目标队友名称
     * @param bus        消息总线
     * @return 操作结果描述（含 request_id）
     */
    private static String handleShutdownRequest(String teammate, MessageBus bus) {
        String reqId = UUID.randomUUID().toString().substring(0, 8);
        shutdownRequests.put(reqId, new ConcurrentHashMap<>(Map.of(
                "target", teammate, "status", "pending")));
        bus.send("lead", teammate, "Please shut down gracefully.",
                "shutdown_request", Map.of("request_id", reqId));
        return "Shutdown request " + reqId + " sent to '" + teammate + "' (status: pending)";
    }

    /**
     * 检查 shutdown 请求状态。
     * <p>
     * 根据 request_id 查询 tracker 中的状态（pending / approved / rejected）。
     * <p>
     * 对应 Python 原版：check_shutdown_status(request_id)
     *
     * @param requestId 要查询的请求 ID
     * @return 状态 JSON 字符串
     */
    private static String checkShutdownStatus(String requestId) {
        var req = shutdownRequests.get(requestId);
        if (req == null) return "{\"error\": \"not found\"}";
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(req);
        } catch (Exception e) {
            return req.toString();
        }
    }

    /**
     * Lead 审批 teammate 的计划。
     * <p>
     * 根据 request_id 找到对应的 plan 请求，更新状态，
     * 并将审批结果发送回提交者。
     * <p>
     * 对应 Python 原版：handle_plan_review(request_id, approve, feedback)
     *
     * @param requestId 计划请求 ID
     * @param approve   是否批准
     * @param feedback  审批反馈（可为 null）
     * @param bus       消息总线
     * @return 审批结果描述
     */
    private static String handlePlanReview(String requestId, boolean approve,
                                           String feedback, MessageBus bus) {
        var req = planRequests.get(requestId);
        if (req == null) return "Error: Unknown plan request_id '" + requestId + "'";

        // 更新状态
        req.put("status", approve ? "approved" : "rejected");

        // 发送审批结果给提交者
        bus.send("lead", (String) req.get("from"),
                feedback != null ? feedback : "",
                "plan_approval_response",
                Map.of("request_id", requestId, "approve", approve,
                        "feedback", feedback != null ? feedback : ""));

        return "Plan " + req.get("status") + " for '" + req.get("from") + "'";
    }

    // ==================== Agent 核心循环 ====================

    /**
     * Agent 核心循环（Lead 专用，带收件箱注入）。
     * <p>
     * 与标准 agentLoop 的区别：
     * <ul>
     *   <li>在每次 LLM 调用之前，先读取 lead 的收件箱</li>
     *   <li>将收件箱消息以 &lt;inbox&gt; 标签注入对话</li>
     *   <li>确保 Lead 能及时看到 teammate 的协议响应</li>
     * </ul>
     * <pre>
     *   while (stopReason == TOOL_USE) {
     *       read inbox and inject;     // 新增：注入收件箱消息
     *       response = LLM(messages, tools);
     *       execute tools;
     *       append results;
     *   }
     * </pre>
     *
     * @param client        Anthropic API 客户端
     * @param paramsBuilder 消息创建参数构建器
     * @param toolHandlers  工具分发表
     * @param bus           消息总线
     */
    @SuppressWarnings("unchecked")
    private static void agentLoop(AnthropicClient client,
                                  MessageCreateParams.Builder paramsBuilder,
                                  Map<String, Function<Map<String, Object>, String>> toolHandlers,
                                  MessageBus bus) {
        while (true) {
            // ---- 0. 读取 lead 收件箱并注入对话 ----
            // 每次 LLM 调用前检查收件箱，确保协议消息及时处理
            var inbox = bus.readInbox("lead");
            if (!inbox.isEmpty()) {
                try {
                    // 用 <inbox> 标签包裹，方便模型识别
                    String inboxJson = MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(inbox);
                    paramsBuilder.addUserMessage("<inbox>" + inboxJson + "</inbox>");
                } catch (Exception ignored) {}
            }

            // ---- 1. 调用 LLM ----
            Message response = client.messages().create(paramsBuilder.build());

            // ---- 2. 将 assistant 回复追加到历史 ----
            paramsBuilder.addMessage(response);

            // ---- 3. 检查是否需要继续执行工具 ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                // 模型决定停止对话，打印文本回复
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(textBlock ->
                            System.out.println(textBlock.text()));
                }
                return;
            }

            // ---- 4. 遍历 content blocks，执行工具调用 ----
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    String toolName = toolUse.name();

                    Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());
                    if (input == null) input = Map.of();

                    // 分发执行
                    Function<Map<String, Object>, String> handler = toolHandlers.get(toolName);
                    String output;
                    if (handler != null) {
                        output = handler.apply(input);
                    } else {
                        output = "Unknown tool: " + toolName;
                    }

                    // 打印工具执行日志
                    System.out.println(bold("> " + toolName) + ":");
                    System.out.println(dim("  " + output.substring(0, Math.min(output.length(), PREVIEW_LEN))));

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
     * S16 REPL 主循环。
     * <p>
     * 整体流程：
     * <pre>
     * 1. 构建客户端和加载模型
     * 2. 初始化 MessageBus 和 TeammateManager
     * 3. 定义 Lead 的 12 个工具
     * 4. 注册工具处理器（含协议工具）
     * 5. 进入 REPL：读取输入 → 执行 Agent 循环 → 输出结果
     * </pre>
     * <p>
     * REPL 命令：
     * <ul>
     *   <li>/team — 列出所有队友及状态</li>
     *   <li>/inbox — 查看 lead 的收件箱</li>
     *   <li>q / exit — 退出</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        // ---- 1. 构建客户端和加载模型 ----
        AnthropicClient client = buildClient();
        String model = loadModel();

        // ---- 2. 初始化消息总线和队友管理器 ----
        Path teamDir = WORK_DIR.resolve(".team");
        MessageBus bus = new MessageBus(teamDir.resolve("inbox"));
        TeammateManager teammateManager = new TeammateManager(teamDir, bus, client, model);

        // ---- 3. Lead 系统提示词 ----
        String systemPrompt = "You are a team lead at " + WORK_DIR
                + ". Manage teammates with shutdown and plan approval protocols.";

        // ---- 4. 定义 Lead 的 12 个工具 ----
        List<Tool> tools = List.of(
                // 基础工具（4 个）
                defineTool("bash", "Run a shell command.",
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

                // 团队管理工具（3 个）
                defineTool("spawn_teammate",
                        "Spawn a persistent teammate (runs in background thread).",
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
                                        "enum", List.of("message", "broadcast", "shutdown_request",
                                                "shutdown_response", "plan_approval_response"))),
                        List.of("to", "content")),

                // 消息工具（2 个）
                defineTool("read_inbox", "Read and drain the lead's inbox.",
                        Map.of(), null),
                defineTool("broadcast", "Broadcast a message to all teammates.",
                        Map.of("content", Map.of("type", "string")),
                        List.of("content")),

                // 协议工具（3 个）
                defineTool("shutdown_request",
                        "Request a teammate to shut down gracefully. Returns request_id for tracking.",
                        Map.of("teammate", Map.of("type", "string")),
                        List.of("teammate")),
                defineTool("shutdown_response",
                        "Check the status of a shutdown request by request_id.",
                        Map.of("request_id", Map.of("type", "string")),
                        List.of("request_id")),
                defineTool("plan_approval",
                        "Approve or reject a teammate's plan by request_id.",
                        Map.of("request_id", Map.of("type", "string"),
                                "approve", Map.of("type", "boolean"),
                                "feedback", Map.of("type", "string")),
                        List.of("request_id", "approve"))
        );

        // ---- 5. 注册工具处理器 ----
        Map<String, Function<Map<String, Object>, String>> toolHandlers = new LinkedHashMap<>();

        // bash：执行 shell 命令
        toolHandlers.put("bash", input -> {
            String command = (String) input.get("command");
            if (command == null || command.isBlank()) return "Error: command is required";
            return runBash(command);
        });
        // read_file：读取文件
        toolHandlers.put("read_file", input -> {
            String path = (String) input.get("path");
            if (path == null || path.isBlank()) return "Error: path is required";
            Integer limit = null;
            Object limitObj = input.get("limit");
            if (limitObj instanceof Number num) limit = num.intValue();
            return runRead(path, limit);
        });
        // write_file：写入文件
        toolHandlers.put("write_file", input -> {
            String path = (String) input.get("path");
            String content = (String) input.get("content");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (content == null) return "Error: content is required";
            return runWrite(path, content);
        });
        // edit_file：精确文本替换
        toolHandlers.put("edit_file", input -> {
            String path = (String) input.get("path");
            String oldText = (String) input.get("old_text");
            String newText = (String) input.get("new_text");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (oldText == null) return "Error: old_text is required";
            if (newText == null) return "Error: new_text is required";
            return runEdit(path, oldText, newText);
        });
        // spawn_teammate：创建队友
        toolHandlers.put("spawn_teammate", input -> {
            String name = (String) input.get("name");
            String role = (String) input.get("role");
            String prompt = (String) input.get("prompt");
            if (name == null || name.isBlank()) return "Error: name is required";
            if (role == null || role.isBlank()) return "Error: role is required";
            if (prompt == null || prompt.isBlank()) return "Error: prompt is required";
            return teammateManager.spawn(name, role, prompt);
        });
        // list_teammates：列出所有队友
        toolHandlers.put("list_teammates", input -> teammateManager.listAll());
        // send_message：发送消息给队友
        toolHandlers.put("send_message", input -> {
            String to = (String) input.get("to");
            String content = (String) input.get("content");
            String msgType = (String) input.getOrDefault("msg_type", "message");
            if (to == null || to.isBlank()) return "Error: to is required";
            if (content == null) return "Error: content is required";
            return bus.send("lead", to, content, msgType, null);
        });
        // read_inbox：读取 lead 收件箱
        toolHandlers.put("read_inbox", input -> {
            try {
                var messages = bus.readInbox("lead");
                return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(messages);
            } catch (Exception e) {
                return "[]";
            }
        });
        // broadcast：广播给所有队友
        toolHandlers.put("broadcast", input -> {
            String content = (String) input.get("content");
            if (content == null || content.isBlank()) return "Error: content is required";
            return bus.broadcast("lead", content, teammateManager.memberNames());
        });

        // ---- 协议工具处理器 ----
        // shutdown_request：Lead 发起关闭请求
        toolHandlers.put("shutdown_request", input -> {
            String teammate = (String) input.get("teammate");
            if (teammate == null || teammate.isBlank()) return "Error: teammate is required";
            return handleShutdownRequest(teammate, bus);
        });
        // shutdown_response：Lead 检查关闭请求状态
        toolHandlers.put("shutdown_response", input -> {
            String requestId = (String) input.get("request_id");
            if (requestId == null || requestId.isBlank()) return "Error: request_id is required";
            return checkShutdownStatus(requestId);
        });
        // plan_approval：Lead 审批计划
        toolHandlers.put("plan_approval", input -> {
            String requestId = (String) input.get("request_id");
            Boolean approve = (Boolean) input.get("approve");
            String feedback = (String) input.get("feedback");
            if (requestId == null || requestId.isBlank()) return "Error: request_id is required";
            if (approve == null) return "Error: approve is required";
            return handlePlanReview(requestId, approve, feedback, bus);
        });

        // ---- 6. 构建消息参数 ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(systemPrompt);

        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }

        // ---- 7. REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println(bold("S16 Team Protocols")
                + " — 12 tools: bash, read_file, write_file, edit_file, "
                + "spawn_teammate, list_teammates, send_message, read_inbox, broadcast, "
                + "shutdown_request, shutdown_response, plan_approval");
        System.out.println("[Commands: /team to list teammates, /inbox to check messages]");
        System.out.println("Type 'q' or 'exit' to quit.\n");

        while (true) {
            System.out.print(cyan("s16 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();

            // 空输入或退出命令
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // /team：列出所有队友及状态
            if ("/team".equals(query)) {
                System.out.println(teammateManager.listAll());
                continue;
            }

            // /inbox：查看 lead 收件箱
            if ("/inbox".equals(query)) {
                try {
                    var msgs = bus.readInbox("lead");
                    System.out.println(msgs.isEmpty() ? "Inbox empty."
                            : MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(msgs));
                } catch (Exception e) {
                    System.out.println("Inbox empty.");
                }
                continue;
            }

            // 追加用户消息到对话历史
            paramsBuilder.addUserMessage(query);

            // 执行 Agent 循环（LLM 调用 + 工具执行 + 收件箱注入）
            try {
                agentLoop(client, paramsBuilder, toolHandlers, bus);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }
            System.out.println();
        }

        System.out.println(dim("Bye!"));
    }
}
