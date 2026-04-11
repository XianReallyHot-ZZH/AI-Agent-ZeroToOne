package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvBuilder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * S19：MCP 插件系统 —— 完全自包含实现（不依赖 core/、tools/、util/ 包）。
 * <p>
 * 外部进程可以暴露工具，Agent 可以在少量标准化后像普通工具一样使用它们。
 * <p>
 * 最小路径：
 *   1. 启动 MCP 服务器进程
 *   2. 向它查询有哪些工具
 *   3. 加上前缀并注册这些工具
 *   4. 将匹配的调用路由到对应服务器
 * <p>
 * 插件多加了一层：发现。一个微型清单文件告诉 Agent 要启动哪个外部服务器。
 * <p>
 * 关键洞察："外部工具应该进入同一个工具管线，而不是形成一个完全独立的世界。"
 * 这意味着共享权限检查和标准化的 tool_result 载荷。
 * <p>
 * 阅读顺序：
 * 1. CapabilityPermissionGate —— 外部工具仍然经过同一个控制门
 * 2. MCPClient —— 一个服务器连接如何暴露工具规格和工具调用
 * 3. PluginLoader —— 清单文件如何声明外部服务器
 * 4. MCPToolRouter / buildToolPool —— 原生和外部工具如何合并为一个池
 * <p>
 * 本文件将所有基础设施内联：
 * - CapabilityPermissionGate：共享权限门（内部类）
 * - MCPClient：最小 stdio MCP 客户端，使用 JSON-RPC 2.0（内部类）
 * - MCPToolRouter：MCP 工具路由器，分发 mcp__ 前缀工具（内部类）
 * - PluginLoader：插件发现器，扫描 .claude-plugin/plugin.json（内部类）
 * - buildClient()：构建 Anthropic API 客户端
 * - loadModel()：从环境变量加载模型 ID
 * - defineTool()：构建 SDK Tool 定义
 * - runBash()：执行 shell 命令（OS 自适应、危险命令拦截、超时、输出截断）
 * - runRead()：读取文件内容（路径沙箱、输出截断）
 * - runWrite()：写入文件（自动创建目录、路径沙箱）
 * - runEdit()：精确文本替换（单次替换、Pattern.quote 字面量匹配）
 * - safePath()：路径沙箱校验，防止路径穿越
 * - buildToolPool()：组装完整工具池（原生 + MCP，原生优先）
 * - handleToolCall()：分发到原生处理器或 MCP 路由器
 * - normalizeToolResult()：用 source/risk/status 元数据包装输出
 * - agentLoop()：统一原生 + MCP 的 Agent 循环（含权限门）
 * - jsonValueToObject()：JsonValue → 普通 Java 对象转换
 * - ANSI 输出：终端彩色文本
 * <p>
 * 对应 Python 原版：s19_mcp_plugin.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s19_mcp_plugin.py">Python 原版</a>
 */
public class S19McpPlugin {

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

    /** 系统提示词：告诉模型它是一个编码 Agent，同时拥有原生和 MCP 工具 */
    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + WORK_DIR + ". Use tools to solve tasks.\n"
            + "You have both native tools and MCP tools available.\n"
            + "MCP tools are prefixed with mcp__{server}__{tool}.\n"
            + "All capabilities pass through the same permission gate before execution.";

    /** Jackson ObjectMapper，用于 MCP JSON-RPC 消息的序列化/反序列化 */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // ==================== ANSI 颜色输出 ====================
    // 终端彩色文本，让 REPL 和工具日志更易读

    private static final String ANSI_RESET  = "\033[0m";
    private static final String ANSI_BOLD   = "\033[1m";
    private static final String ANSI_DIM    = "\033[2m";
    private static final String ANSI_CYAN   = "\033[36m";
    private static final String ANSI_RED    = "\033[31m";
    private static final String ANSI_GREEN  = "\033[32m";
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

    private static String bold(String text)  { return ansi(ANSI_BOLD, text); }
    private static String dim(String text)   { return ansi(ANSI_DIM, text); }
    private static String cyan(String text)  { return ansi(ANSI_CYAN, text); }
    private static String red(String text)   { return ansi(ANSI_RED, text); }
    private static String green(String text) { return ansi(ANSI_GREEN, text); }
    private static String yellow(String text){ return ansi(ANSI_YELLOW, text); }

    // ==================== 内部类：CapabilityPermissionGate ====================

    /**
     * 共享权限门：原生工具和外部能力都经过同一个控制面。
     * <p>
     * MCP 不绕过控制面。原生工具和 MCP 工具都先被标准化为能力意图，
     * 然后经过同一个 allow / ask 策略。
     * <p>
     * 风险等级：
     * - read：只读操作（read/list/get/show/search/query/inspect），自动允许
     * - write：写操作（write/edit/bash 非危险命令），需要确认
     * - high：高风险操作（delete/remove/drop/shutdown + 危险 bash 命令），需要确认
     * <p>
     * 模式：
     * - default：read 自动允许，write/high 需要确认
     * - auto：read/write 自动允许，high 需要确认
     */
    static class CapabilityPermissionGate {

        /** 只读能力前缀 */
        private static final List<String> READ_PREFIXES = List.of(
                "read", "list", "get", "show", "search", "query", "inspect"
        );

        /** 高风险能力前缀 */
        private static final List<String> HIGH_RISK_PREFIXES = List.of(
                "delete", "remove", "drop", "shutdown"
        );

        /** 允许的模式列表 */
        private static final List<String> PERMISSION_MODES = List.of("default", "auto");

        /** 当前权限模式 */
        final String mode;

        /** 构造权限门，默认使用 "default" 模式 */
        CapabilityPermissionGate() {
            this("default");
        }

        /** 构造权限门，指定模式 */
        CapabilityPermissionGate(String mode) {
            this.mode = PERMISSION_MODES.contains(mode) ? mode : "default";
        }

        /**
         * 标准化工具调用：提取 source/server/tool/risk。
         * <p>
         * MCP 工具名格式为 mcp__{server}__{tool}，需要解析出各部分。
         * 原生工具直接使用工具名。
         *
         * @param toolName  工具名称
         * @param toolInput 工具输入参数
         * @return 包含 source、server、tool、risk 的标准化意图
         */
        Map<String, Object> normalize(String toolName, Map<String, Object> toolInput) {
            String source;
            String serverName;
            String actualTool;

            if (toolName.startsWith("mcp__")) {
                // 解析 mcp__{server}__{tool} 格式
                String[] parts = toolName.split("__", 3);
                if (parts.length == 3) {
                    serverName = parts[1];
                    actualTool = parts[2];
                } else {
                    serverName = "unknown";
                    actualTool = toolName;
                }
                source = "mcp";
            } else {
                serverName = null;
                actualTool = toolName;
                source = "native";
            }

            // 根据工具名判断风险等级
            String lowered = actualTool.toLowerCase();
            String risk;

            if ("read_file".equals(actualTool) || startsWithAny(lowered, READ_PREFIXES)) {
                // 只读操作
                risk = "read";
            } else if ("bash".equals(actualTool)) {
                // bash 需要检查命令内容
                String command = toolInput != null && toolInput.get("command") instanceof String s
                        ? s : "";
                risk = "high";
                for (String token : DANGEROUS_COMMANDS) {
                    if (command.contains(token)) {
                        risk = "high";
                        break;
                    }
                    risk = "write"; // 非危险 bash 命令为 write 级别
                }
            } else if (startsWithAny(lowered, HIGH_RISK_PREFIXES)) {
                risk = "high";
            } else {
                risk = "write";
            }

            // 构建标准化意图
            Map<String, Object> intent = new LinkedHashMap<>();
            intent.put("source", source);
            intent.put("server", serverName);
            intent.put("tool", actualTool);
            intent.put("risk", risk);
            return intent;
        }

        /**
         * 检查字符串是否以列表中任一前缀开头。
         */
        private boolean startsWithAny(String str, List<String> prefixes) {
            for (String prefix : prefixes) {
                if (str.startsWith(prefix)) return true;
            }
            return false;
        }

        /**
         * 权限检查：根据风险等级和模式决定行为。
         *
         * @param toolName  工具名称
         * @param toolInput 工具输入参数
         * @return 包含 behavior（allow/ask/deny）、reason、intent 的决策
         */
        Map<String, Object> check(String toolName, Map<String, Object> toolInput) {
            Map<String, Object> intent = normalize(toolName, toolInput);
            String risk = (String) intent.get("risk");

            // 只读能力始终允许
            if ("read".equals(risk)) {
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("behavior", "allow");
                decision.put("reason", "Read capability");
                decision.put("intent", intent);
                return decision;
            }

            // auto 模式下非高风险自动允许
            if ("auto".equals(this.mode) && !"high".equals(risk)) {
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("behavior", "allow");
                decision.put("reason", "Auto mode for non-high-risk capability");
                decision.put("intent", intent);
                return decision;
            }

            // 高风险能力需要确认
            if ("high".equals(risk)) {
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("behavior", "ask");
                decision.put("reason", "High-risk capability requires confirmation");
                decision.put("intent", intent);
                return decision;
            }

            // 其他状态变更能力需要确认
            Map<String, Object> decision = new LinkedHashMap<>();
            decision.put("behavior", "ask");
            decision.put("reason", "State-changing capability requires confirmation");
            decision.put("intent", intent);
            return decision;
        }

        /**
         * 交互式询问用户是否允许操作。
         *
         * @param intent    标准化的意图
         * @param toolInput 工具输入参数（用于预览）
         * @return true 表示用户允许，false 表示拒绝
         */
        boolean askUser(Map<String, Object> intent, Map<String, Object> toolInput) {
            // 预览：截断到 200 字符
            String preview = toolInput != null ? toolInput.toString() : "{}";
            if (preview.length() > 200) {
                preview = preview.substring(0, 200);
            }

            // 构建来源标识
            String source;
            String server = (String) intent.get("server");
            if (server != null) {
                source = intent.get("source") + ":" + server + "/" + intent.get("tool");
            } else {
                source = intent.get("source") + ":" + intent.get("tool");
            }

            System.out.println();
            System.out.println("  " + yellow("[Permission]") + " " + source
                    + " risk=" + intent.get("risk") + ": " + preview);
            System.out.print("  Allow? (y/n): ");

            try {
                Scanner scanner = new Scanner(System.in);
                String answer = scanner.nextLine().trim().toLowerCase();
                return "y".equals(answer) || "yes".equals(answer);
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ==================== 内部类：MCPClient ====================

    /**
     * 最小 stdio MCP 客户端，使用 JSON-RPC 2.0。
     * <p>
     * 足以教授核心架构，而不需要拖拽读者穿越每个传输、
     * 认证流程或市场细节。
     * <p>
     * 通信协议：
     * 1. 发送 initialize 请求（protocolVersion "2024-11-05"）
     * 2. 接收 initialize 结果
     * 3. 发送 notifications/initialized 通知（无 id）
     * 4. 之后按需使用 tools/list、tools/call
     * <p>
     * JSON-RPC 2.0 格式：
     * 发送：{"jsonrpc":"2.0","id":N,"method":"...","params":{...}}
     * 接收：{"jsonrpc":"2.0","id":N,"result":{...}} 或 {"jsonrpc":"2.0","id":N,"error":{...}}
     */
    static class MCPClient {

        /** 服务器名称标识 */
        final String serverName;

        /** 启动命令 */
        private final String command;

        /** 命令参数 */
        private final List<String> args;

        /** 子进程环境变量 */
        private final Map<String, String> env;

        /** 子进程 */
        private Process process;

        /** 子进程 stdin 写入流 */
        private Writer stdinWriter;

        /** 子进程 stdout 读取流 */
        private BufferedReader stdoutReader;

        /** 自增请求 ID */
        private int requestId = 0;

        /** 从服务器获取的工具列表缓存 */
        private List<Map<String, Object>> tools = new ArrayList<>();

        /**
         * 构造 MCP 客户端。
         *
         * @param serverName 服务器名称标识
         * @param command    启动命令
         * @param args       命令参数列表
         * @param env        额外环境变量（会合并到系统环境变量中）
         */
        MCPClient(String serverName, String command, List<String> args, Map<String, String> env) {
            this.serverName = serverName;
            this.command = command;
            this.args = args != null ? args : new ArrayList<>();
            // 合并系统环境变量和额外环境变量
            this.env = new LinkedHashMap<>();
            this.env.putAll(System.getenv());
            if (env != null) {
                this.env.putAll(env);
            }
        }

        /**
         * 连接 MCP 服务器：启动子进程，发送 initialize + initialized。
         *
         * @return true 表示连接成功
         */
        boolean connect() {
            try {
                // 构建命令列表
                List<String> commandList = new ArrayList<>();
                commandList.add(this.command);
                commandList.addAll(this.args);

                ProcessBuilder pb = new ProcessBuilder(commandList);
                pb.environment().putAll(this.env);
                pb.redirectErrorStream(false); // stderr 单独处理，不混入 stdout

                this.process = pb.start();
                this.stdinWriter = new OutputStreamWriter(this.process.getOutputStream());
                this.stdoutReader = new BufferedReader(
                        new InputStreamReader(this.process.getInputStream()));

                // 发送 initialize 请求
                Map<String, Object> initParams = new LinkedHashMap<>();
                initParams.put("protocolVersion", "2024-11-05");
                initParams.put("capabilities", Map.of());
                initParams.put("clientInfo", Map.of(
                        "name", "teaching-agent",
                        "version", "1.0"
                ));

                send("initialize", initParams);

                // 接收 initialize 响应
                Map<String, Object> response = recv();
                if (response != null && response.containsKey("result")) {
                    // 发送 initialized 通知（无 id，不需要响应）
                    sendNotification("notifications/initialized");
                    return true;
                }
            } catch (FileNotFoundException e) {
                System.out.println("[MCP] Server command not found: " + this.command);
            } catch (Exception e) {
                System.out.println("[MCP] Connection failed: " + e.getMessage());
            }
            return false;
        }

        /**
         * 从 MCP 服务器获取可用工具列表。
         *
         * @return 工具列表
         */
        List<Map<String, Object>> listTools() {
            send("tools/list", Map.of());
            Map<String, Object> response = recv();
            if (response != null && response.containsKey("result")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> toolList = (List<Map<String, Object>>) result.get("tools");
                if (toolList != null) {
                    this.tools = toolList;
                }
            }
            return this.tools;
        }

        /**
         * 在 MCP 服务器上执行工具调用。
         *
         * @param toolName  工具名称（不带 mcp__ 前缀的原始名称）
         * @param arguments 工具参数
         * @return 工具执行结果字符串
         */
        String callTool(String toolName, Map<String, Object> arguments) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments != null ? arguments : Map.of());

            send("tools/call", params);

            Map<String, Object> response = recv();
            if (response != null && response.containsKey("result")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
                if (content != null) {
                    StringBuilder sb = new StringBuilder();
                    for (Map<String, Object> c : content) {
                        if (sb.length() > 0) sb.append("\n");
                        Object text = c.get("text");
                        sb.append(text != null ? text.toString() : c.toString());
                    }
                    return sb.toString();
                }
            }
            if (response != null && response.containsKey("error")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> error = (Map<String, Object>) response.get("error");
                return "MCP Error: " + error.getOrDefault("message", "unknown");
            }
            return "MCP Error: no response";
        }

        /**
         * 将 MCP 工具转换为 Agent 工具格式。
         * <p>
         * 使用 mcp__{server_name}__{tool_name} 前缀约定。
         *
         * @return Agent 格式的工具列表
         */
        List<Map<String, Object>> getAgentTools() {
            List<Map<String, Object>> agentTools = new ArrayList<>();
            for (Map<String, Object> tool : this.tools) {
                String originalName = (String) tool.get("name");
                String prefixedName = "mcp__" + this.serverName + "__" + originalName;

                Map<String, Object> agentTool = new LinkedHashMap<>();
                agentTool.put("name", prefixedName);
                agentTool.put("description", tool.getOrDefault("description", ""));
                // inputSchema 如果不存在则提供默认值
                @SuppressWarnings("unchecked")
                Map<String, Object> inputSchema = (Map<String, Object>) tool.get("inputSchema");
                if (inputSchema == null) {
                    inputSchema = Map.of("type", "object", "properties", Map.of());
                }
                agentTool.put("inputSchema", inputSchema);
                agentTool.put("_mcp_server", this.serverName);
                agentTool.put("_mcp_tool", originalName);

                agentTools.add(agentTool);
            }
            return agentTools;
        }

        /**
         * 断开连接：关闭 MCP 服务器进程。
         */
        void disconnect() {
            if (this.process != null) {
                try {
                    sendNotification("shutdown");
                } catch (Exception ignored) {
                    // 发送 shutdown 失败不影响后续清理
                }
                this.process.destroy();
                try {
                    this.process.waitFor(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    this.process.destroyForcibly();
                }
                this.process = null;
            }
        }

        /**
         * 发送 JSON-RPC 2.0 请求（带 id）。
         * <p>
         * 格式：{"jsonrpc":"2.0","id":N,"method":"...","params":{...}}
         *
         * @param method JSON-RPC 方法名
         * @param params 方法参数
         */
        private void send(String method, Map<String, Object> params) {
            if (this.process == null || !this.process.isAlive()) return;

            this.requestId++;
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("jsonrpc", "2.0");
            envelope.put("id", this.requestId);
            envelope.put("method", method);
            envelope.put("params", params);

            String json;
            try {
                json = JSON_MAPPER.writeValueAsString(envelope) + "\n";
            } catch (Exception e) {
                return;
            }

            try {
                this.stdinWriter.write(json);
                this.stdinWriter.flush();
            } catch (IOException e) {
                // 管道可能已断开
            }
        }

        /**
         * 发送 JSON-RPC 2.0 通知（无 id，不需要响应）。
         * <p>
         * 格式：{"jsonrpc":"2.0","method":"...","params":{...}}
         *
         * @param method JSON-RPC 方法名
         */
        private void sendNotification(String method) {
            if (this.process == null || !this.process.isAlive()) return;

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("jsonrpc", "2.0");
            envelope.put("method", method);

            String json;
            try {
                json = JSON_MAPPER.writeValueAsString(envelope) + "\n";
            } catch (Exception e) {
                return;
            }

            try {
                this.stdinWriter.write(json);
                this.stdinWriter.flush();
            } catch (IOException e) {
                // 管道可能已断开
            }
        }

        /**
         * 接收 JSON-RPC 2.0 响应。
         * <p>
         * 从 stdout 读取一行 JSON，解析为 Map。
         *
         * @return 解析后的响应 Map，如果读取失败返回 null
         */
        private Map<String, Object> recv() {
            if (this.process == null || !this.process.isAlive()) return null;

            try {
                String line = this.stdoutReader.readLine();
                if (line != null && !line.isBlank()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = JSON_MAPPER.readValue(line, Map.class);
                    return parsed;
                }
            } catch (Exception e) {
                // JSON 解析失败或 IO 错误
            }
            return null;
        }
    }

    // ==================== 内部类：MCPToolRouter ====================

    /**
     * MCP 工具路由器：将 mcp__ 前缀的工具调用分发到正确的 MCP 服务器。
     * <p>
     * MCP 工具以 mcp__{server}__{tool} 为前缀，与原生工具共存于同一个工具池。
     * 路由器剥离前缀并分发到对应的 MCPClient。
     */
    static class MCPToolRouter {

        /** 已注册的 MCP 客户端：服务器名称 → MCPClient */
        private final Map<String, MCPClient> clients = new LinkedHashMap<>();

        /**
         * 注册一个 MCP 客户端。
         *
         * @param client MCP 客户端实例
         */
        void registerClient(MCPClient client) {
            this.clients.put(client.serverName, client);
        }

        /**
         * 检查工具名是否为 MCP 工具（以 mcp__ 开头）。
         *
         * @param toolName 工具名称
         * @return true 表示是 MCP 工具
         */
        boolean isMcpTool(String toolName) {
            return toolName.startsWith("mcp__");
        }

        /**
         * 路由 MCP 工具调用到正确的服务器。
         * <p>
         * 解析 mcp__{server}__{tool} 格式，提取服务器名和工具名，
         * 然后调用对应 MCPClient 的 callTool 方法。
         *
         * @param toolName  完整的 MCP 工具名（带 mcp__ 前缀）
         * @param arguments 工具参数
         * @return 工具执行结果
         */
        String call(String toolName, Map<String, Object> arguments) {
            String[] parts = toolName.split("__", 3);
            if (parts.length != 3) {
                return "Error: Invalid MCP tool name: " + toolName;
            }

            String serverName = parts[1];
            String actualTool = parts[2];

            MCPClient client = this.clients.get(serverName);
            if (client == null) {
                return "Error: MCP server not found: " + serverName;
            }

            return client.callTool(actualTool, arguments);
        }

        /**
         * 收集所有已连接 MCP 服务器的工具列表。
         *
         * @return 所有 MCP 工具的列表
         */
        List<Map<String, Object>> getAllTools() {
            List<Map<String, Object>> allTools = new ArrayList<>();
            for (MCPClient client : this.clients.values()) {
                allTools.addAll(client.getAgentTools());
            }
            return allTools;
        }

        /**
         * 获取已注册的 MCP 客户端映射（用于 REPL 的 /mcp 命令和清理）。
         *
         * @return 服务器名称 → MCPClient 的映射
         */
        Map<String, MCPClient> getClients() {
            return Collections.unmodifiableMap(this.clients);
        }
    }

    // ==================== 内部类：PluginLoader ====================

    /**
     * 插件发现器：从 .claude-plugin/ 目录加载插件。
     * <p>
     * 教学版实现了最小可用的插件流程：
     * 读取清单文件，发现 MCP 服务器配置，注册它们。
     */
    static class PluginLoader {

        /** 搜索目录列表 */
        private final List<Path> searchDirs;

        /** 已加载的插件：插件名 → 清单内容 */
        private final Map<String, Map<String, Object>> plugins = new LinkedHashMap<>();

        /**
         * 构造插件加载器。
         *
         * @param searchDirs 搜索目录路径列表
         */
        PluginLoader(List<Path> searchDirs) {
            this.searchDirs = searchDirs != null ? searchDirs : List.of(WORK_DIR);
        }

        /**
         * 扫描目录中的 .claude-plugin/plugin.json 清单文件。
         *
         * @return 发现的插件名称列表
         */
        List<String> scan() {
            List<String> found = new ArrayList<>();
            for (Path searchDir : this.searchDirs) {
                Path pluginDir = searchDir.resolve(".claude-plugin");
                Path manifestPath = pluginDir.resolve("plugin.json");

                if (Files.exists(manifestPath)) {
                    try {
                        String content = Files.readString(manifestPath);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> manifest = JSON_MAPPER.readValue(content, Map.class);

                        String name = (String) manifest.getOrDefault("name",
                                pluginDir.getParent().getFileName().toString());
                        this.plugins.put(name, manifest);
                        found.add(name);
                    } catch (Exception e) {
                        System.out.println("[Plugin] Failed to load " + manifestPath + ": " + e.getMessage());
                    }
                }
            }
            return found;
        }

        /**
         * 从已加载的插件中提取 MCP 服务器配置。
         *
         * @return 服务器配置映射：{pluginName}__{serverName} → {command, args, env}
         */
        Map<String, Map<String, Object>> getMcpServers() {
            Map<String, Map<String, Object>> servers = new LinkedHashMap<>();
            for (var entry : this.plugins.entrySet()) {
                String pluginName = entry.getKey();
                Map<String, Object> manifest = entry.getValue();

                @SuppressWarnings("unchecked")
                Map<String, Object> mcpServers = (Map<String, Object>) manifest.get("mcpServers");
                if (mcpServers != null) {
                    for (var serverEntry : mcpServers.entrySet()) {
                        String serverName = pluginName + "__" + serverEntry.getKey();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> config = (Map<String, Object>) serverEntry.getValue();
                        servers.put(serverName, config);
                    }
                }
            }
            return servers;
        }
    }

    // ==================== 全局实例 ====================

    /** 共享权限门 */
    private static final CapabilityPermissionGate permissionGate = new CapabilityPermissionGate();

    /** MCP 工具路由器 */
    private static final MCPToolRouter mcpRouter = new MCPToolRouter();

    /** 插件加载器 */
    private static final PluginLoader pluginLoader = new PluginLoader(List.of(WORK_DIR));

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
     * 支持自定义 baseUrl（用于第三方 API 兼容端点）。
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
     * 将 name/description/properties/required 转换为 Anthropic SDK 的 Tool 对象。
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

    /**
     * 从 MCP 工具的 inputSchema 构建一个 SDK Tool 定义。
     * <p>
     * MCP 工具的 inputSchema 已经是 JSON Schema 格式，只需要提取 properties 和 required。
     *
     * @param name        前缀后的工具名称（mcp__{server}__{tool}）
     * @param description 工具描述
     * @param inputSchema MCP 工具的 inputSchema
     * @return 构建好的 Tool 对象
     */
    @SuppressWarnings("unchecked")
    private static Tool defineMcpTool(String name, String description,
                                      Map<String, Object> inputSchema) {
        var schemaBuilder = Tool.InputSchema.builder();

        // 提取 properties
        Object propsObj = inputSchema.get("properties");
        if (propsObj instanceof Map) {
            schemaBuilder.properties(JsonValue.from(propsObj));
        } else {
            schemaBuilder.properties(JsonValue.from(Map.of()));
        }

        // 提取 required
        Object requiredObj = inputSchema.get("required");
        if (requiredObj instanceof List) {
            schemaBuilder.putAdditionalProperty("required", JsonValue.from(requiredObj));
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

    // ==================== 原生工具实现 ====================

    /**
     * 执行 shell 命令。
     * <p>
     * 安全特性：危险命令黑名单、超时、输出截断、OS 自适应。
     *
     * @param command 要执行的 shell 命令
     * @return 命令输出
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
     *
     * @param path 相对文件路径
     * @return 文件内容字符串
     */
    private static String runRead(String path) {
        try {
            Path safePath = safePath(path);
            String result = Files.readString(safePath);
            return result.length() > MAX_OUTPUT
                    ? result.substring(0, MAX_OUTPUT)
                    : result;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 写入文件内容。
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
            return "Wrote " + content.length() + " bytes";
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 精确文本替换（仅替换第一次出现）。
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

    // ==================== 原生工具定义和处理器 ====================

    /** 原生工具定义列表 */
    private static final List<Tool> NATIVE_TOOLS = List.of(
            // bash：执行 shell 命令
            defineTool("bash", "Run a shell command.",
                    Map.of("command", Map.of("type", "string")),
                    List.of("command")),

            // read_file：读取文件内容
            defineTool("read_file", "Read file contents.",
                    Map.of("path", Map.of("type", "string")),
                    List.of("path")),

            // write_file：写入文件内容
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

    /** 原生工具名 → 处理函数的分发表 */
    private static final Map<String, Function<Map<String, Object>, String>> NATIVE_HANDLERS = new LinkedHashMap<>();

    static {
        NATIVE_HANDLERS.put("bash", input -> {
            String command = (String) input.get("command");
            if (command == null || command.isBlank()) return "Error: command is required";
            return runBash(command);
        });
        NATIVE_HANDLERS.put("read_file", input -> {
            String path = (String) input.get("path");
            if (path == null || path.isBlank()) return "Error: path is required";
            return runRead(path);
        });
        NATIVE_HANDLERS.put("write_file", input -> {
            String path = (String) input.get("path");
            String content = (String) input.get("content");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (content == null) return "Error: content is required";
            return runWrite(path, content);
        });
        NATIVE_HANDLERS.put("edit_file", input -> {
            String path = (String) input.get("path");
            String oldText = (String) input.get("old_text");
            String newText = (String) input.get("new_text");
            if (path == null || path.isBlank()) return "Error: path is required";
            if (oldText == null) return "Error: old_text is required";
            if (newText == null) return "Error: new_text is required";
            return runEdit(path, oldText, newText);
        });
    }

    // ==================== 工具池构建 ====================

    /**
     * 组装完整的工具池：原生 + MCP 工具。
     * <p>
     * 原生工具在名称冲突时优先，确保本地核心在添加外部工具后仍然可预测。
     *
     * @return 完整的工具列表（SDK Tool 对象）
     */
    private static List<Tool> buildToolPool() {
        List<Tool> allTools = new ArrayList<>(NATIVE_TOOLS);

        // 收集原生工具名称，用于去重
        Set<String> nativeNames = new HashSet<>();
        for (Tool tool : NATIVE_TOOLS) {
            nativeNames.add(tool.name());
        }

        // 添加 MCP 工具（跳过与原生工具同名的）
        List<Map<String, Object>> mcpTools = mcpRouter.getAllTools();
        for (Map<String, Object> mcpTool : mcpTools) {
            String name = (String) mcpTool.get("name");
            if (!nativeNames.contains(name)) {
                String description = (String) mcpTool.getOrDefault("description", "");
                @SuppressWarnings("unchecked")
                Map<String, Object> inputSchema = (Map<String, Object>) mcpTool.get("inputSchema");
                allTools.add(defineMcpTool(name, description,
                        inputSchema != null ? inputSchema : Map.of("properties", Map.of())));
            }
        }

        return allTools;
    }

    // ==================== 工具调用分发 ====================

    /**
     * 分发工具调用到原生处理器或 MCP 路由器。
     *
     * @param toolName  工具名称
     * @param toolInput 工具输入参数
     * @return 工具执行结果
     */
    private static String handleToolCall(String toolName, Map<String, Object> toolInput) {
        // MCP 工具路由到 MCP 路由器
        if (mcpRouter.isMcpTool(toolName)) {
            return mcpRouter.call(toolName, toolInput);
        }

        // 原生工具查分发表
        Function<Map<String, Object>, String> handler = NATIVE_HANDLERS.get(toolName);
        if (handler != null) {
            return handler.apply(toolInput);
        }

        return "Unknown tool: " + toolName;
    }

    // ==================== 工具结果标准化 ====================

    /**
     * 用 source/risk/status 元数据包装工具输出。
     * <p>
     * 所有工具（原生和 MCP）的输出都经过标准化，确保一致性。
     * 检测输出中的 "Error:" 或 "MCP Error:" 来判断状态。
     *
     * @param toolName 工具名称
     * @param output   原始输出字符串
     * @param intent   标准化的意图（可为 null，自动从 toolName 推导）
     * @return JSON 格式的标准化结果
     */
    private static String normalizeToolResult(String toolName, String output, Map<String, Object> intent) {
        if (intent == null) {
            intent = permissionGate.normalize(toolName, Map.of());
        }

        // 根据输出内容判断状态
        String status;
        if (output.contains("Error:") || output.contains("MCP Error:")) {
            status = "error";
        } else {
            status = "ok";
        }

        // 截取预览（前 500 字符）
        String preview = output.length() > 500 ? output.substring(0, 500) : output;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", intent.get("source"));
        payload.put("server", intent.get("server"));
        payload.put("tool", intent.get("tool"));
        payload.put("risk", intent.get("risk"));
        payload.put("status", status);
        payload.put("preview", preview);

        try {
            return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return payload.toString();
        }
    }

    // ==================== JsonValue 转换 ====================

    /**
     * 将 SDK 的 JsonValue 转换为普通 Java 对象。
     * <p>
     * 递归转换 Map/List/String/Number/Boolean 等类型。
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

    // ==================== Agent 核心循环 ====================

    /**
     * Agent 核心循环：统一原生 + MCP 的工具池，含权限门。
     * <p>
     * 核心模式：
     * <pre>
     *   while (stopReason == TOOL_USE) {
     *       response = LLM(messages, tools);
     *       check permission for each tool call;
     *       execute allowed tools;
     *       normalize results;
     *       append results;
     *   }
     * </pre>
     * <p>
     * 每次工具调用都经过权限管线后才真正执行：
     * 1. normalize → 提取 source/server/tool/risk
     * 2. check → 根据 risk 和 mode 决定 allow/ask/deny
     * 3. ask user → 如果 behavior == "ask"，交互式询问
     * 4. execute → 执行并标准化结果
     *
     * @param client        Anthropic API 客户端
     * @param model         模型 ID
     * @param paramsBuilder 消息创建参数构建器
     * @param tools         工具定义列表
     */
    @SuppressWarnings("unchecked")
    private static void agentLoop(AnthropicClient client, String model,
                                  MessageCreateParams.Builder paramsBuilder,
                                  List<Tool> tools) {
        while (true) {
            // ---- 1. 调用 LLM ----
            Message response = client.messages().create(paramsBuilder.build());

            // ---- 2. 将 assistant 回复追加到历史 ----
            paramsBuilder.addMessage(response);

            // ---- 3. 检查是否需要继续执行工具 ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                // 模型决定停止，打印文本回复
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(textBlock ->
                            System.out.println(textBlock.text()));
                }
                return;
            }

            // ---- 4. 遍历 content blocks，执行工具调用（含权限检查） ----
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (!block.isToolUse()) continue;

                ToolUseBlock toolUse = block.asToolUse();
                String toolName = toolUse.name();

                // 提取工具输入参数
                Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());
                if (input == null) input = new LinkedHashMap<>();

                // ---- 权限检查 ----
                Map<String, Object> decision = permissionGate.check(toolName, input);
                String behavior = (String) decision.get("behavior");
                String reason = (String) decision.get("reason");
                @SuppressWarnings("unchecked")
                Map<String, Object> intent = (Map<String, Object>) decision.get("intent");

                String output;
                try {
                    if ("deny".equals(behavior)) {
                        // 拒绝
                        output = "Permission denied: " + reason;
                    } else if ("ask".equals(behavior)) {
                        // 需要确认，询问用户
                        if (!permissionGate.askUser(intent, input)) {
                            output = "Permission denied by user: " + reason;
                        } else {
                            output = handleToolCall(toolName, input);
                        }
                    } else {
                        // 允许
                        output = handleToolCall(toolName, input);
                    }
                } catch (Exception e) {
                    output = "Error: " + e.getMessage();
                }

                // 打印工具调用日志
                System.out.println(bold("> " + toolName) + ": "
                        + dim(output.substring(0, Math.min(output.length(), 200))));

                // 标准化工具结果（包装 source/risk/status 元数据）
                String normalizedResult = normalizeToolResult(toolName, output, intent);

                // 构建 tool_result 消息块
                toolResults.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(toolUse.id())
                                .content(normalizedResult)
                                .build()));
            }

            // ---- 5. 将工具结果追加为 user 消息 ----
            paramsBuilder.addUserMessageOfBlockParams(toolResults);
        }
    }

    // ==================== 主程序入口 ====================

    /**
     * REPL 主循环：MCP 插件系统 Agent。
     * <p>
     * 整体流程：
     * <pre>
     * 1. 扫描插件
     * 2. 连接 MCP 服务器
     * 3. 构建工具池（原生 + MCP）
     * 4. 进入 REPL：读取用户输入 → Agent 循环 → 打印结果
     * </pre>
     * <p>
     * REPL 命令：
     * - /tools：列出所有可用工具（原生 + MCP）
     * - /mcp：显示已连接的 MCP 服务器
     * - q/exit：退出
     */
    public static void main(String[] args) {
        // ---- 构建客户端和加载模型 ----
        AnthropicClient client = buildClient();
        String model = loadModel();

        // ---- 扫描插件 ----
        List<String> found = pluginLoader.scan();
        if (!found.isEmpty()) {
            System.out.println("[Plugins loaded: " + String.join(", ", found) + "]");

            // 连接 MCP 服务器
            Map<String, Map<String, Object>> mcpServers = pluginLoader.getMcpServers();
            for (var entry : mcpServers.entrySet()) {
                String serverName = entry.getKey();
                Map<String, Object> config = entry.getValue();

                String cmd = (String) config.getOrDefault("command", "");
                @SuppressWarnings("unchecked")
                List<String> serverArgs = (List<String>) config.get("args");
                @SuppressWarnings("unchecked")
                Map<String, String> serverEnv = (Map<String, String>) config.get("env");

                MCPClient mcpClient = new MCPClient(serverName, cmd, serverArgs, serverEnv);
                if (mcpClient.connect()) {
                    mcpClient.listTools();
                    mcpRouter.registerClient(mcpClient);
                    System.out.println("[MCP] Connected to " + serverName);
                }
            }
        }

        // ---- 构建工具池 ----
        List<Tool> tools = buildToolPool();
        int toolCount = tools.size();
        int mcpCount = mcpRouter.getAllTools().size();
        System.out.println("[Tool pool: " + toolCount + " tools (" + mcpCount + " from MCP)]");

        // ---- 构建消息参数 ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(SYSTEM_PROMPT);

        // 注册所有工具（每次循环都重新注册，因为工具池可能变化）
        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }

        // ---- REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println(bold("S19 MCP Plugin System") + " — native + MCP tools with permission gate");
        System.out.println("Commands: /tools (list all), /mcp (show servers), q/exit to quit.\n");

        while (true) {
            // 打印提示符（青色 "s19 >>"）
            System.out.print(cyan("s19 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();

            // 空输入跳过
            if (query.isEmpty()) continue;

            // 退出命令
            if ("q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // /tools 命令：列出所有工具
            if ("/tools".equals(query)) {
                System.out.println(dim("  Native tools:"));
                for (Tool tool : NATIVE_TOOLS) {
                    System.out.println("         " + tool.name() + ": "
                            + tool.description().map(d -> d.length() > 60 ? d.substring(0, 60) : d).orElse(""));
                }
                List<Map<String, Object>> mcpTools = mcpRouter.getAllTools();
                if (!mcpTools.isEmpty()) {
                    System.out.println(dim("  MCP tools:"));
                    for (Map<String, Object> tool : mcpTools) {
                        String name = (String) tool.get("name");
                        String desc = (String) tool.getOrDefault("description", "");
                        if (desc.length() > 60) desc = desc.substring(0, 60);
                        System.out.println("  " + green("[MCP]") + " " + name + ": " + desc);
                    }
                }
                continue;
            }

            // /mcp 命令：显示已连接的 MCP 服务器
            if ("/mcp".equals(query)) {
                Map<String, MCPClient> clients = mcpRouter.getClients();
                if (clients.isEmpty()) {
                    System.out.println("  (no MCP servers connected)");
                } else {
                    for (var entry : clients.entrySet()) {
                        List<Map<String, Object>> serverTools = entry.getValue().getAgentTools();
                        System.out.println("  " + entry.getKey() + ": " + serverTools.size() + " tools");
                    }
                }
                continue;
            }

            // 追加用户消息到对话历史
            paramsBuilder.addUserMessage(query);

            // 执行 Agent 循环
            try {
                agentLoop(client, model, paramsBuilder, tools);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }
            System.out.println(); // 每轮结束后空一行
        }

        // ---- 清理：断开所有 MCP 连接 ----
        for (MCPClient mcpClient : mcpRouter.getClients().values()) {
            mcpClient.disconnect();
        }

        System.out.println(dim("Bye!"));
    }
}
