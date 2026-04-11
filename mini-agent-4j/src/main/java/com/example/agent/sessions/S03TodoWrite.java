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
 * S03：TodoWrite —— 让模型自己跟踪进度（完全自包含实现）。
 * <p>
 * 本课在 S02 的 4 个工具基础上，新增第 5 个工具：todo。
 * 模型通过 TodoManager 维护一个结构化的任务列表，用于多步骤工作。
 * <p>
 * 关键洞察："Agent 可以自己跟踪进度——而我能看到。"
 * <p>
 * 新增机制 —— Nag Reminder（催促提醒）：
 * - 每轮工具执行后，检查模型是否调用了 todo 工具
 * - 如果连续 3 轮未更新 todo，且仍有未完成任务，注入 &lt;reminder&gt; 提醒
 * - 这是"外部装置（Harness）干预 Agent 行为"的第一个例子
 * <p>
 * 本文件将所有基础设施内联（与 S02 完全相同）：
 * - buildClient() / loadModel() / defineTool()
 * - runBash() / runRead() / runWrite() / runEdit() / safePath()
 * - agentLoop() / jsonValueToObject() / ANSI helpers
 * - 新增：TodoManager（私有静态内部类）
 * <p>
 * 对应 Python 原版：s03_todo_write.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s03_todo_write.py">Python 原版</a>
 */
public class S03TodoWrite {

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

    /** 连续多少轮未更新 todo 后触发催促提醒，与 Python 原版 PLAN_REMINDER_INTERVAL = 3 对齐 */
    private static final int PLAN_REMINDER_INTERVAL = 3;

    /** 系统提示词：告诉模型它是一个编码 Agent，需要用 todo 工具规划多步骤工作 */
    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + WORK_DIR + ".\n"
            + "Use the todo tool for multi-step work.\n"
            + "Keep exactly one step in_progress when a task has multiple steps.\n"
            + "Refresh the plan as work advances. Prefer tools over prose.";

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
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 精确文本替换（仅替换第一次出现）。
     * <p>
     * 使用 Pattern.quote() 确保 old_text 作为字面量匹配，
     * 不会被当作正则表达式解析。使用 Matcher.quoteReplacement() 确保
     * new_text 中的 \ 和 $ 也被正确处理。
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

    // ==================== TodoManager（私有静态内部类） ====================

    /**
     * Todo 管理器：模型通过结构化列表跟踪自身进度。
     * <p>
     * 这是 S03 的核心新增。模型在处理多步骤任务时，可以用 todo 工具
     * 来维护一个"会话计划"（Session Plan），让自己不会迷失在复杂任务中。
     * <p>
     * 核心规则（与 Python 原版完全对齐）：
     * - 最多 12 项（保持计划精简）
     * - 最多 1 个 in_progress 状态（聚焦当前步骤）
     * - 每项必须有 content 和 status
     * - activeForm 是可选的"正在进行中"标签
     * <p>
     * 渲染格式：
     * <pre>
     * [ ] 审查代码
     * [>] 修复 bug (#123)  ← activeForm 会显示在 in_progress 项后面
     * [x] 编写测试
     *
     * (1/3 completed)
     * </pre>
     * <p>
     * 对应 Python 原版中的 TodoManager 类。
     */
    private static class TodoManager {

        /** 单个计划项的数据结构，对应 Python 的 PlanItem dataclass */
        private static class PlanItem {
            /** 任务内容描述 */
            final String content;
            /** 状态：pending / in_progress / completed */
            final String status;
            /** 可选的"正在进行中"标签，如 "Fixing auth bug" */
            final String activeForm;

            PlanItem(String content, String status, String activeForm) {
                this.content = content;
                this.status = status;
                this.activeForm = activeForm;
            }
        }

        /** 当前计划项列表 */
        private List<PlanItem> items = new ArrayList<>();

        /** 连续未更新 todo 的轮数（用于 nag reminder 判断） */
        private int roundsSinceUpdate = 0;

        /**
         * 更新整个计划列表（全量替换）。
         * <p>
         * 模型每次调用 todo 工具时，传入完整的 items 数组。
         * 本方法会做严格的校验，确保计划符合规则：
         * - 不超过 12 项
         * - 每项必须有 content
         * - status 只能是 pending / in_progress / completed
         * - 最多 1 个 in_progress
         * <p>
         * 对应 Python：TodoManager.update(items)
         *
         * @param rawItems 从工具输入解析出的项目列表
         * @return 渲染后的计划状态字符串（作为工具结果返回给模型）
         * @throws IllegalArgumentException 校验失败时抛出
         */
        @SuppressWarnings("unchecked")
        public String update(List<?> rawItems) {
            // 最多 12 项，保持计划精简
            if (rawItems.size() > 12) {
                throw new IllegalArgumentException("Keep the session plan short (max 12 items)");
            }

            List<PlanItem> normalized = new ArrayList<>();
            int inProgressCount = 0;

            for (int i = 0; i < rawItems.size(); i++) {
                Map<String, Object> raw = (Map<String, Object>) rawItems.get(i);

                // 提取并清理 content
                String content = String.valueOf(raw.getOrDefault("content", "")).trim();
                // 提取并规范化 status（小写）
                String status = String.valueOf(raw.getOrDefault("status", "pending")).toLowerCase();
                // 提取并清理 activeForm（可选字段）
                String activeForm = String.valueOf(raw.getOrDefault("activeForm", "")).trim();

                // 校验 content 不能为空
                if (content.isEmpty()) {
                    throw new IllegalArgumentException("Item " + i + ": content required");
                }
                // 校验 status 必须是合法值
                if (!Set.of("pending", "in_progress", "completed").contains(status)) {
                    throw new IllegalArgumentException(
                            "Item " + i + ": invalid status '" + status + "'");
                }
                // 统计 in_progress 数量
                if ("in_progress".equals(status)) {
                    inProgressCount++;
                }

                normalized.add(new PlanItem(content, status, activeForm));
            }

            // 最多 1 个 in_progress（保持聚焦）
            if (inProgressCount > 1) {
                throw new IllegalArgumentException("Only one plan item can be in_progress");
            }

            // 全量替换
            this.items = normalized;
            // 重置计数器：模型刚刚更新了计划
            this.roundsSinceUpdate = 0;
            return render();
        }

        /**
         * 记录一轮未更新 todo。
         * <p>
         * 每轮工具执行结束后，如果没有调用 todo 工具，就调用此方法递增计数器。
         * 当计数器达到 PLAN_REMINDER_INTERVAL (3) 时，触发催促提醒。
         * <p>
         * 对应 Python：TodoManager.note_round_without_update()
         */
        public void noteRoundWithoutUpdate() {
            roundsSinceUpdate++;
        }

        /**
         * 重置计数器（模型调用了 todo 工具时调用）。
         * <p>
         * 对应 Python：TODO.state.rounds_since_update = 0
         */
        public void resetRoundCounter() {
            roundsSinceUpdate = 0;
        }

        /**
         * 生成催促提醒文本。
         * <p>
         * 条件：
         * 1. 计划列表不为空（有计划才需要催促）
         * 2. 连续未更新轮数 >= PLAN_REMINDER_INTERVAL (3)
         * 3. 还有未完成的项目（全部完成就不催了）
         * <p>
         * 对应 Python：TodoManager.reminder()
         *
         * @return 提醒文本，如果不需要提醒则返回 null
         */
        public String reminder() {
            // 没有计划，不需要催促
            if (items.isEmpty()) return null;
            // 还没到催促间隔
            if (roundsSinceUpdate < PLAN_REMINDER_INTERVAL) return null;
            // 检查是否有未完成的项目
            boolean hasOpen = items.stream()
                    .anyMatch(item -> !"completed".equals(item.status));
            if (!hasOpen) return null;
            // 生成提醒
            return "<reminder>Refresh your current plan before continuing.</reminder>";
        }

        /**
         * 渲染计划列表为可读字符串。
         * <p>
         * 格式示例：
         * <pre>
         * [ ] 审查代码
         * [>] 修复 bug (Fixing auth logic)
         * [x] 编写测试
         *
         * (1/3 completed)
         * </pre>
         * <p>
         * 标记符号说明：
         * - [ ] pending（待处理）
         * - [>] in_progress（进行中）
         * - [x] completed（已完成）
         * <p>
         * activeForm 只在 in_progress 项后面显示。
         * <p>
         * 对应 Python：TodoManager.render()
         *
         * @return 渲染后的字符串
         */
        public String render() {
            // 空计划
            if (items.isEmpty()) {
                return "No session plan yet.";
            }

            List<String> lines = new ArrayList<>();
            for (PlanItem item : items) {
                // 根据状态选择标记符号
                String marker = switch (item.status) {
                    case "pending"      -> "[ ]";
                    case "in_progress"  -> "[>]";
                    case "completed"    -> "[x]";
                    default             -> "[?]";
                };
                String line = marker + " " + item.content;
                // in_progress 项附加 activeForm（如果有的话）
                if ("in_progress".equals(item.status) && !item.activeForm.isEmpty()) {
                    line += " (" + item.activeForm + ")";
                }
                lines.add(line);
            }

            // 底部汇总：完成数 / 总数
            long completed = items.stream()
                    .filter(item -> "completed".equals(item.status))
                    .count();
            lines.add("\n(" + completed + "/" + items.size() + " completed)");

            return String.join("\n", lines);
        }

        /**
         * 检查是否有未完成的计划项。
         * <p>
         * 用于 nag reminder 判断：只有存在未完成项时才催促。
         */
        public boolean hasOpenItems() {
            return items.stream().anyMatch(item -> !"completed".equals(item.status));
        }
    }

    // ==================== Agent 核心循环 ====================

    /**
     * Agent 核心循环：LLM 调用 → 工具执行 → 结果回传。
     * <p>
     * 与 S02 相比，唯一的区别是增加了 Nag Reminder 机制：
     * - 每轮工具执行后，跟踪 todo 工具是否被调用
     * - 如果连续 3 轮未更新，且有未完成任务，在结果前插入提醒文本
     * <p>
     * 这展示了"外部装置（Harness）干预 Agent 行为"的模式：
     * 循环本身不变，我们在工具结果回传前插入额外信息，
     * 引导模型刷新它的计划。
     * <p>
     * 对应 Python 原版：agent_loop(messages) 函数。
     *
     * @param client       Anthropic API 客户端
     * @param model        模型 ID
     * @param paramsBuilder 消息创建参数构建器（包含已有对话历史）
     * @param tools        工具定义列表（发送给 LLM）
     * @param toolHandlers 工具分发表：工具名 → 处理函数
     * @param todo         TodoManager 实例（用于 nag reminder）
     */
    @SuppressWarnings("unchecked")
    private static void agentLoop(AnthropicClient client, String model,
                                  MessageCreateParams.Builder paramsBuilder,
                                  List<Tool> tools,
                                  Map<String, Function<Map<String, Object>, String>> toolHandlers,
                                  TodoManager todo) {
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

            // ---- 4. 遍历 content blocks，执行工具调用 ----
            List<ContentBlockParam> toolResults = new ArrayList<>();
            boolean usedTodo = false; // 跟踪本轮是否调用了 todo 工具

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
                    try {
                        if (handler != null) {
                            output = handler.apply(input);
                        } else {
                            output = "Unknown tool: " + toolName;
                        }
                    } catch (Exception e) {
                        output = "Error: " + e.getMessage();
                    }

                    // 打印工具调用日志：> toolName: 输出预览
                    System.out.println(bold("> " + toolName) + ":");
                    System.out.println(dim("  " + output.substring(0, Math.min(output.length(), 200))));

                    // 标记本轮是否调用了 todo
                    if ("todo".equals(toolName)) {
                        usedTodo = true;
                    }

                    // 构造 tool_result 消息块，回传给 LLM
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                }
            }

            // ---- 5. Nag Reminder 机制（S03 核心新增） ----
            // 如果模型调用了 todo，重置计数器；否则递增
            if (usedTodo) {
                todo.resetRoundCounter();
            } else {
                todo.noteRoundWithoutUpdate();
                // 检查是否需要催促
                String reminder = todo.reminder();
                if (reminder != null) {
                    // 在结果列表最前面插入提醒文本块
                    // 这样模型在看到工具结果之前会先看到提醒
                    toolResults.add(0, ContentBlockParam.ofText(
                            TextBlockParam.builder()
                                    .text(reminder)
                                    .build()));
                }
            }

            // ---- 6. 将工具结果追加为 user 消息 ----
            // API 要求 tool_result 必须以 user 角色发送
            paramsBuilder.addUserMessageOfBlockParams(toolResults);
        }
    }

    // ==================== 从消息历史提取最终文本 ====================

    /**
     * 从消息内容块列表中提取纯文本。
     * <p>
     * Agent 循环结束后，最后一条 assistant 消息可能包含多个文本块。
     * 本方法将它们拼接成完整的输出文本，用于在 REPL 中显示。
     * <p>
     * 对应 Python 原版：extract_text(content) 函数。
     *
     * @param content 消息内容（ContentBlock 列表）
     * @return 拼接后的纯文本，如果没有文本则返回空字符串
     */
    private static String extractText(List<ContentBlock> content) {
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block != null) {
                block.text().ifPresent(textBlock -> {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(textBlock.text());
                });
            }
        }
        return sb.toString().trim();
    }

    // ==================== 主程序入口 ====================

    /**
     * REPL 主循环：读取用户输入 → 追加到对话历史 → 执行 Agent 循环 → 打印结果。
     * <p>
     * 整体流程与 S02 相同，只是：
     * 1. 多了一个 todo 工具（第 5 个工具）
     * 2. 系统提示词增加了"Use the todo tool for multi-step work."
     * 3. Agent 循环内嵌了 Nag Reminder 机制
     * <p>
     * 提示符：青色 "s03 >>"
     */
    public static void main(String[] args) {
        // ---- 构建客户端和加载模型 ----
        AnthropicClient client = buildClient();
        String model = loadModel();

        // ---- 创建 TodoManager 实例 ----
        // 这是 S03 的核心组件，贯穿整个会话生命周期
        TodoManager todo = new TodoManager();

        // ---- 定义 5 个工具（S02 的 4 个 + 新增 todo） ----
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
                defineTool("edit_file", "Replace exact text in file once.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),

                // todo：重写当前会话计划（S03 核心新增）
                // 模型通过此工具维护结构化的任务列表
                defineTool("todo", "Rewrite the current session plan for multi-step work.",
                        Map.of("items", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "content", Map.of("type", "string"),
                                                "status", Map.of(
                                                        "type", "string",
                                                        "enum", List.of("pending", "in_progress", "completed")),
                                                "activeForm", Map.of(
                                                        "type", "string",
                                                        "description", "Optional present-continuous label.")),
                                        "required", List.of("content", "status")))),
                        List.of("items"))
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
        // todo 工具处理器：调用 TodoManager.update() 并返回渲染后的计划
        toolHandlers.put("todo", input -> {
            @SuppressWarnings("unchecked")
            List<?> items = (List<?>) input.get("items");
            if (items == null) return "Error: items is required";
            return todo.update(items);
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
        System.out.println(bold("S03 Todo Write") + " — 5 tools: bash, read_file, write_file, edit_file, todo");
        System.out.println("Type 'q' or 'exit' to quit.\n");

        // 用列表来保存历史消息，以便在循环外读取最后一条 assistant 消息
        List<Message> messageHistory = new ArrayList<>();

        while (true) {
            // 打印提示符（青色 "s03 >>"）
            System.out.print(cyan("s03 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();
            // 空输入或退出命令 → 结束
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // 追加用户消息到对话历史
            paramsBuilder.addUserMessage(query);

            // 执行 Agent 循环（LLM 调用 + 工具执行 + nag reminder）
            try {
                agentLoop(client, model, paramsBuilder, tools, toolHandlers, todo);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }
            System.out.println(); // 每轮结束后空一行，视觉分隔
        }

        System.out.println(dim("Bye!"));
    }
}
