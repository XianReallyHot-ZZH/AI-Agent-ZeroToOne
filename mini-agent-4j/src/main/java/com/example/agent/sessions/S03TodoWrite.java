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
 * - buildClient() / loadDotenv() / defineTool()
 * - runBash() / runRead() / runWrite() / runEdit() / safePath()
 * - runOneTurn() + agentLoop() + extractText() / jsonValueToObject() / ANSI helpers
 * - 新增：PlanningState + TodoManager（私有静态内部类）
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

    /** 最大输出 token 数（与 Python 原版一致） */
    private static final long MAX_TOKENS = 8000L;

    /** 系统提示词：告诉模型它是一个编码 Agent，需要用 todo 工具规划多步骤工作 */
    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + WORK_DIR + ".\n"
            + "Use the todo tool for multi-step work.\n"
            + "Keep exactly one step in_progress when a task has multiple steps.\n"
            + "Refresh the plan as work advances. Prefer tools over prose.";

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

    private static String ansi(String code, String text) {
        return ANSI_SUPPORTED ? code + text + ANSI_RESET : text;
    }

    private static String bold(String text)   { return ansi(ANSI_BOLD, text); }
    private static String dim(String text)    { return ansi(ANSI_DIM, text); }
    private static String cyan(String text)   { return ansi(ANSI_CYAN, text); }
    private static String red(String text)    { return ansi(ANSI_RED, text); }
    private static String yellow(String text) { return ansi(ANSI_YELLOW, text); }
    private static String gray(String text)   { return ansi(ANSI_GRAY, text); }
    private static String green(String text)  { return ansi(ANSI_GREEN, text); }

    // ==================== LoopState 数据类 ====================

    /**
     * Agent 循环状态 —— 与 S01/S02 Java 版的 LoopState 结构一致。
     */
    static class LoopState {
        final MessageCreateParams.Builder paramsBuilder;
        int turnCount;
        String transitionReason;
        Message lastResponse;

        LoopState(MessageCreateParams.Builder paramsBuilder) {
            this.paramsBuilder = paramsBuilder;
            this.turnCount = 1;
            this.transitionReason = null;
            this.lastResponse = null;
        }
    }

    // ==================== PlanningState 数据类 ====================

    /**
     * 计划状态 —— 对应 Python 原版的 PlanningState dataclass。
     * <p>
     * 将计划状态从 TodoManager 中独立出来，与 Python 的数据模型对齐。
     * Python 原版中 TodoManager 持有 PlanningState，PlanningState 持有 items 和 rounds_since_update。
     */
    static class PlanningState {
        /** 当前计划项列表 */
        List<TodoManager.PlanItem> items = new ArrayList<>();
        /** 连续未更新 todo 的轮数（用于 nag reminder 判断） */
        int roundsSinceUpdate = 0;
    }

    // ==================== 环境变量 & 客户端构建 ====================

    private static Dotenv loadDotenv() {
        return Dotenv.configure()
                .ignoreIfMissing()
                .load();
    }

    /**
     * 构建 Anthropic API 客户端。
     * <p>
     * 如果设置了 ANTHROPIC_BASE_URL，则设置 ANTHROPIC_AUTH_TOKEN 为空避免冲突
     * （与 Python 原版 os.environ.pop 行为对齐）。
     */
    private static AnthropicClient buildClient(Dotenv dotenv) {
        String baseUrl = dotenv.get("ANTHROPIC_BASE_URL");

        if (baseUrl != null && !baseUrl.isBlank()) {
            // Python: os.environ.pop("ANTHROPIC_AUTH_TOKEN", None)
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

    private static Path safePath(String relativePath) {
        Path resolved = WORK_DIR.resolve(relativePath).normalize().toAbsolutePath();
        if (!resolved.startsWith(WORK_DIR)) {
            throw new SecurityException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }

    // ==================== 工具实现 ====================

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
     * 对应 Python 原版中的 TodoManager 类。
     */
    private static class TodoManager {

        /** 单个计划项的数据结构，对应 Python 的 PlanItem dataclass */
        private static class PlanItem {
            final String content;
            final String status;
            final String activeForm;

            PlanItem(String content, String status, String activeForm) {
                this.content = content;
                this.status = status;
                this.activeForm = activeForm;
            }
        }

        /** 计划状态（对应 Python 的 PlanningState dataclass） */
        private final PlanningState state = new PlanningState();

        /**
         * 更新整个计划列表（全量替换）。
         * <p>
         * 对应 Python：TodoManager.update(items)
         */
        @SuppressWarnings("unchecked")
        public String update(List<?> rawItems) {
            if (rawItems.size() > 12) {
                throw new IllegalArgumentException("Keep the session plan short (max 12 items)");
            }

            List<PlanItem> normalized = new ArrayList<>();
            int inProgressCount = 0;

            for (int i = 0; i < rawItems.size(); i++) {
                Map<String, Object> raw = (Map<String, Object>) rawItems.get(i);

                String content = String.valueOf(raw.getOrDefault("content", "")).trim();
                String status = String.valueOf(raw.getOrDefault("status", "pending")).toLowerCase();
                String activeForm = String.valueOf(raw.getOrDefault("activeForm", "")).trim();

                if (content.isEmpty()) {
                    throw new IllegalArgumentException("Item " + i + ": content required");
                }
                if (!Set.of("pending", "in_progress", "completed").contains(status)) {
                    throw new IllegalArgumentException(
                            "Item " + i + ": invalid status '" + status + "'");
                }
                if ("in_progress".equals(status)) {
                    inProgressCount++;
                }

                normalized.add(new PlanItem(content, status, activeForm));
            }

            if (inProgressCount > 1) {
                throw new IllegalArgumentException("Only one plan item can be in_progress");
            }

            this.state.items = normalized;
            this.state.roundsSinceUpdate = 0;
            return render();
        }

        /**
         * 记录一轮未更新 todo。
         * <p>
         * 对应 Python：TodoManager.note_round_without_update()
         */
        public void noteRoundWithoutUpdate() {
            state.roundsSinceUpdate++;
        }

        /**
         * 重置计数器（模型调用了 todo 工具时调用）。
         * <p>
         * 对应 Python：TODO.state.rounds_since_update = 0
         */
        public void resetRoundCounter() {
            state.roundsSinceUpdate = 0;
        }

        /**
         * 生成催促提醒文本。
         * <p>
         * 条件（与 Python 原版对齐）：
         * 1. 计划列表不为空
         * 2. 连续未更新轮数 >= PLAN_REMINDER_INTERVAL (3)
         * <p>
         * 额外条件（Java 版优化，Python 原版没有）：
         * 3. 还有未完成的项目（全部完成时不催促，避免无意义的提醒）
         * <p>
         * 对应 Python：TodoManager.reminder()
         *
         * @return 提醒文本，如果不需要提醒则返回 null
         */
        public String reminder() {
            if (state.items.isEmpty()) return null;
            if (state.roundsSinceUpdate < PLAN_REMINDER_INTERVAL) return null;
            // Java 版额外优化：全部完成时不催促
            boolean hasOpen = state.items.stream()
                    .anyMatch(item -> !"completed".equals(item.status));
            if (!hasOpen) return null;
            return "<reminder>Refresh your current plan before continuing.</reminder>";
        }

        /**
         * 渲染计划列表为可读字符串。
         * <p>
         * 对应 Python：TodoManager.render()
         */
        public String render() {
            if (state.items.isEmpty()) {
                return "No session plan yet.";
            }

            List<String> lines = new ArrayList<>();
            for (PlanItem item : state.items) {
                String marker = switch (item.status) {
                    case "pending"      -> "[ ]";
                    case "in_progress"  -> "[>]";
                    case "completed"    -> "[x]";
                    default             -> "[?]";
                };
                String line = marker + " " + item.content;
                if ("in_progress".equals(item.status) && !item.activeForm.isEmpty()) {
                    line += " (" + item.activeForm + ")";
                }
                lines.add(line);
            }

            long completed = state.items.stream()
                    .filter(item -> "completed".equals(item.status))
                    .count();
            lines.add("\n(" + completed + "/" + state.items.size() + " completed)");

            return String.join("\n", lines);
        }
    }

    // ==================== Agent 核心循环 ====================

    /**
     * 执行一轮 Agent 循环 —— 与 S01/S02 Java 版 runOneTurn() 结构一致。
     * <p>
     * 与 S02 的唯一区别是增加了 Nag Reminder 机制：
     * 每轮工具执行后，跟踪 todo 工具是否被调用，
     * 如果连续 3 轮未更新且有未完成任务，在结果前插入提醒文本。
     */
    @SuppressWarnings("unchecked")
    private static boolean runOneTurn(AnthropicClient client, LoopState state,
                                      Map<String, Function<Map<String, Object>, String>> toolHandlers,
                                      TodoManager todo) {
        // ---- 1. 调用 LLM ----
        Message response = client.messages().create(state.paramsBuilder.build());

        // ---- 2. 将 assistant 回复追加到历史 ----
        state.paramsBuilder.addMessage(response);

        // ---- 3. 保存最后一次响应 ----
        state.lastResponse = response;

        // ---- 4. 检查停止原因 ----
        boolean isToolUse = response.stopReason()
                .map(StopReason.TOOL_USE::equals)
                .orElse(false);

        if (!isToolUse) {
            System.out.println(bold("[turn " + state.turnCount + "] "
                    + "transition: " + stopReasonLabel(response.stopReason())
                    + " → final response"));
            state.transitionReason = null;
            return false;
        }

        // ---- 5. 打印回合标题 ----
        System.out.println(bold("[turn " + state.turnCount + "] "
                + "transition: " + stopReasonLabel(response.stopReason())
                + " → executing tools"));

        // ---- 6. 遍历 content blocks，执行工具调用 ----
        List<ContentBlockParam> toolResults = new ArrayList<>();
        boolean usedTodo = false;

        for (ContentBlock block : response.content()) {
            if (block.isToolUse()) {
                ToolUseBlock toolUse = block.asToolUse();
                String toolName = toolUse.name();

                Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());
                if (input == null) input = Map.of();

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

                // 打印工具调用日志
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

                if ("todo".equals(toolName)) {
                    usedTodo = true;
                }

                toolResults.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(toolUse.id())
                                .content(output)
                                .build()));
            }
        }

        // ---- 7. 空工具结果防御 ----
        if (toolResults.isEmpty()) {
            state.transitionReason = null;
            return false;
        }

        // ---- 8. Nag Reminder 机制（S03 核心新增） ----
        if (usedTodo) {
            todo.resetRoundCounter();
        } else {
            todo.noteRoundWithoutUpdate();
            String reminder = todo.reminder();
            if (reminder != null) {
                // 在结果列表最前面插入提醒文本块
                toolResults.add(0, ContentBlockParam.ofText(
                        TextBlockParam.builder()
                                .text(reminder)
                                .build()));
            }
        }

        // ---- 9. 将工具结果追加为 user 消息 ----
        state.paramsBuilder.addUserMessageOfBlockParams(toolResults);

        // ---- 10. 更新循环状态 ----
        state.turnCount++;
        state.transitionReason = "tool_result";
        return true;
    }

    /**
     * Agent 核心循环 —— 与 S01/S02 Java 版 agentLoop() 结构一致。
     */
    private static void agentLoop(AnthropicClient client, LoopState state,
                                  Map<String, Function<Map<String, Object>, String>> toolHandlers,
                                  TodoManager todo) {
        while (runOneTurn(client, state, toolHandlers, todo)) {
            // 循环体为空 —— 这是教学版的核心约束
        }
    }

    // ==================== 文本提取 ====================

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
     * 与 S02 版本相同，新增 todo 工具的摘要策略。
     */
    private static String formatInputSummary(String toolName, Map<String, Object> input) {
        if (input == null || input.isEmpty()) return "";

        switch (toolName) {
            case "bash": {
                return String.valueOf(input.get("command"));
            }
            case "read_file": {
                String path = String.valueOf(input.get("path"));
                Object limit = input.get("limit");
                return limit != null ? path + ", limit=" + limit : path;
            }
            case "write_file": {
                String path = String.valueOf(input.get("path"));
                String content = String.valueOf(input.get("content"));
                if (content.length() > 50) {
                    return path + ", content=\"" + content.substring(0, 50) + "...\" (" + content.length() + " chars)";
                }
                return path + ", content=\"" + content + "\"";
            }
            case "edit_file": {
                String path = String.valueOf(input.get("path"));
                String oldText = String.valueOf(input.get("old_text"));
                String newText = String.valueOf(input.get("new_text"));
                String oldSnippet = oldText.length() > 40 ? oldText.substring(0, 40) + "..." : oldText;
                String newSnippet = newText.length() > 40 ? newText.substring(0, 40) + "..." : newText;
                return path + ", \"" + oldSnippet + "\" → \"" + newSnippet + "\"";
            }
            case "todo": {
                // todo: 展示计划项数量和 in_progress 项
                Object itemsObj = input.get("items");
                if (itemsObj instanceof List<?> items) {
                    String inProgress = items.stream()
                            .filter(item -> item instanceof Map)
                            .map(item -> (Map<String, Object>) item)
                            .filter(item -> "in_progress".equals(String.valueOf(item.get("status")).toLowerCase()))
                            .map(item -> String.valueOf(item.get("content")))
                            .findFirst()
                            .orElse(null);
                    String base = items.size() + " items";
                    return inProgress != null ? base + ", in_progress: \"" + truncate(inProgress, 40) + "\"" : base;
                }
                return String.valueOf(itemsObj);
            }
            default: {
                return input.entrySet().stream()
                        .map(e -> e.getKey() + "=" + truncate(String.valueOf(e.getValue()), 60))
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
            }
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }

    // ==================== 主程序入口 ====================

    /**
     * REPL 主循环：与 S02 相同，只是多了 todo 工具和 Nag Reminder。
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

        // ---- 4. 创建 TodoManager 实例 ----
        TodoManager todo = new TodoManager();

        // ---- 5. 定义 5 个工具（S02 的 4 个 + 新增 todo） ----
        List<Tool> tools = List.of(
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

                defineTool("edit_file", "Replace exact text in file once.",
                        Map.of(
                                "path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),

                // todo：重写当前会话计划（S03 核心新增）
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

        // ---- 6. 工具分发表 ----
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
        toolHandlers.put("todo", input -> {
            @SuppressWarnings("unchecked")
            List<?> items = (List<?>) input.get("items");
            if (items == null) return "Error: items is required";
            return todo.update(items);
        });

        // ---- 7. 构建消息参数 ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(SYSTEM_PROMPT);

        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }

        // ---- 8. REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println(bold("S03 Todo Write") + " — 5 tools: bash, read_file, write_file, edit_file, todo");
        System.out.println("Type 'q' or 'exit' to quit.\n");

        while (true) {
            System.out.print(cyan("s03 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            paramsBuilder.addUserMessage(query);

            LoopState state = new LoopState(paramsBuilder);

            try {
                agentLoop(client, state, toolHandlers, todo);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }

            String finalText = extractText(state);
            if (finalText != null && !finalText.isEmpty()) {
                System.out.println(green("─── Response ───────────────────"));
                System.out.println(finalText);
            }

            System.out.println();
        }
    }
}
