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
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * S14：定时调度 —— 完全自包含实现（不依赖 core/、tools/、util/ 包）。
 * <p>
 * Agent 可以通过标准 cron 表达式调度未来的提示词执行。
 * 当调度匹配当前时间时，通知被推送到主对话循环中。
 * <pre>
 *     Cron 表达式: 5 个字段
 *     +-------+-------+-------+-------+-------+
 *     | min   | hour  | dom   | month | dow   |
 *     | 0-59  | 0-23  | 1-31  | 1-12  | 0-6   |
 *     +-------+-------+-------+-------+-------+
 *     示例:
 *       STAR/5 * * * *   -> 每 5 分钟
 *       0 9 * * 1        -> 每周一上午 9:00
 *       30 14 * * *      -> 每天 14:30
 * </pre>
 * 两种持久化模式:
 * <pre>
 *     +--------------------+-------------------------------+
 *     | session-only       | 内存列表，退出后丢失            |
 *     | durable            | .claude/scheduled_tasks.json   |
 *     +--------------------+-------------------------------+
 * </pre>
 * 两种触发模式:
 * <pre>
 *     +--------------------+-------------------------------+
 *     | recurring          | 重复执行，直到删除或 7 天自动过期 |
 *     | one-shot           | 触发一次后自动删除              |
 *     +--------------------+-------------------------------+
 * </pre>
 * 架构:
 * <pre>
 *     +-------------------------------+
 *     |  后台线程                      |
 *     |  (每秒检查一次)                |
 *     |                               |
 *     |  对每个任务:                    |
 *     |    if cronMatches(now):       |
 *     |      将通知加入队列             |
 *     +-------------------------------+
 *               |
 *               v
 *     [notification_queue]
 *               |
 *          (在 agentLoop 顶部排空)
 *               |
 *               v
 *     [在 LLM 调用前作为 user 消息注入]
 * </pre>
 * 关键洞察："调度记住未来的工作，然后在时间到达时将其交还给同一个主循环。"
 * <p>
 * 本文件将所有基础设施内联：
 * - buildClient()：构建 Anthropic API 客户端
 * - loadModel()：从环境变量加载模型 ID
 * - defineTool()：构建 SDK Tool 定义
 * - runBash()：执行 shell 命令
 * - runRead()：读取文件内容
 * - runWrite()：写入文件
 * - runEdit()：精确文本替换
 * - safePath()：路径沙箱校验
 * - CronScheduler：定时调度器内部类（后台线程、通知队列、持久化）
 * - cronMatches()：5 字段 cron 表达式匹配
 * - fieldMatches(): 单字段匹配 (支持 STAR, STAR/N, N-M, N,M)
 * - ANSI 输出：终端彩色文本
 * - agentLoop()：核心 LLM 调用 → 工具执行 → 结果回传循环（带通知排空）
 * - jsonValueToObject()：JsonValue → 普通 Java 对象转换
 * <p>
 * 对应 Python 原版：s14_cron_scheduler.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s14_cron_scheduler.py">Python 原版</a>
 */
public class S14CronScheduler {

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

    /** 持久化任务文件路径 */
    private static final Path SCHEDULED_TASKS_FILE = WORK_DIR.resolve(".claude").resolve("scheduled_tasks.json");

    /** 循环任务自动过期天数 */
    private static final int AUTO_EXPIRY_DAYS = 7;

    /** 需要抖动偏移的分钟数（避免在精确的 :00 和 :30 触发） */
    private static final List<Integer> JITTER_MINUTES = List.of(0, 30);

    /** 抖动偏移最大值（分钟） */
    private static final int JITTER_OFFSET_MAX = 4;

    /** 系统提示词：告诉模型它是一个编码 Agent，可以通过 cron_create 调度未来工作 */
    private static final String SYSTEM_PROMPT =
            "You are a coding agent at " + WORK_DIR + ". Use tools to solve tasks.\n\n"
            + "You can schedule future work with cron_create. "
            + "Tasks fire automatically and their prompts are injected into the conversation.";

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
     */
    private static Dotenv loadDotenv() {
        return new DotenvBuilder()
                .ignoreIfMissing()
                .systemProperties()
                .load();
    }

    /**
     * 构建 Anthropic API 客户端。
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

    // ==================== Cron 表达式匹配 ====================

    /**
     * 检查 5 字段 cron 表达式是否匹配给定的日期时间。
     * <p>
     * 字段顺序: 分钟 小时 日 月 星期
     * 支持: STAR（任意）, STAR/N（步进）, N（精确）, N-M（范围）, N,M（列表）
     * <p>
     * Java 星期映射：DayOfWeek (MONDAY=1..SUNDAY=7) → cron (0=Sunday..6=Saturday)
     * <p>
     * 对应 Python 原版：cron_matches(expr, dt) 函数。
     *
     * @param expr 5 字段 cron 表达式
     * @param dt   要检查的日期时间
     * @return 是否匹配
     */
    public static boolean cronMatches(String expr, LocalDateTime dt) {
        String[] fields = expr.trim().split("\\s+");
        if (fields.length != 5) {
            return false;
        }

        // 提取日期时间各字段值
        int minute = dt.getMinute();
        int hour = dt.getHour();
        int dom = dt.getDayOfMonth();
        int month = dt.getMonthValue();

        // Java DayOfWeek: MONDAY=1..SUNDAY=7, cron: 0=Sunday..6=Saturday
        // 转换: SUNDAY(7)->0, MONDAY(1)->1, ..., SATURDAY(6)->6
        int javaDow = dt.getDayOfWeek().getValue(); // 1=Mon..7=Sun
        int cronDow = javaDow % 7; // 1=Mon..6=Sat, 0=Sun

        int[] values = {minute, hour, dom, month, cronDow};
        // 各字段的取值范围
        int[][] ranges = {
                {0, 59},   // 分钟: 0-59
                {0, 23},   // 小时: 0-23
                {1, 31},   // 日:   1-31
                {1, 12},   // 月:   1-12
                {0, 6}     // 星期: 0-6
        };

        for (int i = 0; i < 5; i++) {
            if (!fieldMatches(fields[i], values[i], ranges[i][0], ranges[i][1])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 匹配单个 cron 字段。
     * <p>
     * 支持的语法:
     * - STAR    - 匹配任意值
     * - STAR/N  - 从 lo 开始每隔 N 匹配
     * - N       - 精确匹配
     * - N-M     - 范围匹配（含端点）
     * - N,M,... - 列表匹配（逗号分隔多个上述语法）
     * <p>
     * 对应 Python 原版：_field_matches(field, value, lo, hi) 函数。
     *
     * @param field cron 字段字符串
     * @param value 当前时间对应的字段值
     * @param lo    该字段的最小值
     * @param hi    该字段的最大值
     * @return 是否匹配
     */
    public static boolean fieldMatches(String field, int value, int lo, int hi) {
        if ("*".equals(field)) {
            return true;
        }

        // 逗号分隔：列表匹配，任一部分匹配即可
        for (String part : field.split(",")) {
            // 步进处理: */N 或 N-M/S
            int step = 1;
            String rangePart = part;
            if (part.contains("/")) {
                String[] split = part.split("/", 2);
                rangePart = split[0];
                step = Integer.parseInt(split[1]);
            }

            if ("*".equals(rangePart)) {
                // */N —— 检查 value 是否在步进网格上
                if ((value - lo) % step == 0) {
                    return true;
                }
            } else if (rangePart.contains("-")) {
                // 范围: N-M
                String[] bounds = rangePart.split("-", 2);
                int start = Integer.parseInt(bounds[0]);
                int end = Integer.parseInt(bounds[1]);
                if (start <= value && value <= end && (value - start) % step == 0) {
                    return true;
                }
            } else {
                // 精确值
                if (Integer.parseInt(rangePart) == value) {
                    return true;
                }
            }
        }

        return false;
    }

    // ==================== CronScheduler 内部类 ====================

    /**
     * 定时调度器：管理计划任务，带后台检查线程。
     * <p>
     * 核心组件：
     * - tasks：任务列表（内存）
     * - notificationQueue：通知队列（后台线程写入，agentLoop 排空）
     * - 后台线程：每秒检查一次，每分钟每任务最多触发一次
     * - 持久化：durable 任务保存到 .claude/scheduled_tasks.json
     * <p>
     * 对应 Python 原版：CronScheduler 类。
     */
    public static class CronScheduler {

        /** 调度任务列表 */
        private final List<Map<String, Object>> tasks = Collections.synchronizedList(new ArrayList<>());

        /** 通知队列：后台线程写入，agentLoop 在每次 LLM 调用前排空 */
        private final ConcurrentLinkedQueue<String> notificationQueue = new ConcurrentLinkedQueue<>();

        /** 停止信号 */
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);

        /** 上次检查的分钟数（避免同一分钟内重复触发） */
        private final AtomicInteger lastCheckMinute = new AtomicInteger(-1);

        /** 后台检查线程 */
        private Thread checkThread;

        /**
         * 启动调度器：加载持久化任务，启动后台检查线程。
         */
        public void start() {
            loadDurable();
            checkThread = new Thread(this::checkLoop, "cron-checker");
            checkThread.setDaemon(true);
            checkThread.start();
            int count = tasks.size();
            if (count > 0) {
                System.out.println("[Cron] Loaded " + count + " scheduled tasks");
            }
        }

        /**
         * 停止调度器：通知后台线程退出并等待其结束。
         */
        public void stop() {
            stopRequested.set(true);
            if (checkThread != null) {
                try {
                    checkThread.join(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * 创建新的调度任务。
         * <p>
         * 对 recurring 任务：如果 cron 指向 :00 或 :30，添加抖动偏移避免精确边界触发。
         *
         * @param cronExpr  5 字段 cron 表达式
         * @param prompt    触发时注入的提示词
         * @param recurring true=循环任务，false=一次性任务
         * @param durable   true=持久化到磁盘，false=仅内存
         * @return 创建结果描述（含任务 ID）
         */
        public String create(String cronExpr, String prompt, boolean recurring, boolean durable) {
            // 生成 8 位短 ID
            String taskId = UUID.randomUUID().toString().substring(0, 8);
            double createdAt = System.currentTimeMillis() / 1000.0;

            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", taskId);
            task.put("cron", cronExpr);
            task.put("prompt", prompt);
            task.put("recurring", recurring);
            task.put("durable", durable);
            task.put("createdAt", createdAt);

            // recurring 任务添加抖动偏移
            if (recurring) {
                task.put("jitter_offset", computeJitter(cronExpr));
            }

            tasks.add(task);
            if (durable) {
                saveDurable();
            }

            String mode = recurring ? "recurring" : "one-shot";
            String store = durable ? "durable" : "session-only";
            return "Created task " + taskId + " (" + mode + ", " + store + "): cron=" + cronExpr;
        }

        /**
         * 删除指定 ID 的调度任务。
         *
         * @param taskId 要删除的任务 ID
         * @return 删除结果描述
         */
        public String delete(String taskId) {
            int before = tasks.size();
            tasks.removeIf(t -> taskId.equals(t.get("id")));
            if (tasks.size() < before) {
                saveDurable();
                return "Deleted task " + taskId;
            }
            return "Task " + taskId + " not found";
        }

        /**
         * 列出所有调度任务。
         *
         * @return 格式化的任务列表字符串
         */
        public String listTasks() {
            if (tasks.isEmpty()) {
                return "No scheduled tasks.";
            }
            StringBuilder sb = new StringBuilder();
            double now = System.currentTimeMillis() / 1000.0;
            for (Map<String, Object> t : tasks) {
                String mode = Boolean.TRUE.equals(t.get("recurring")) ? "recurring" : "one-shot";
                String store = Boolean.TRUE.equals(t.get("durable")) ? "durable" : "session";
                double ageHours = (now - ((Number) t.get("createdAt")).doubleValue()) / 3600.0;
                String promptPreview = ((String) t.get("prompt"));
                if (promptPreview.length() > 60) {
                    promptPreview = promptPreview.substring(0, 60);
                }
                sb.append(String.format("  %s  %s  [%s/%s] (%.1fh old): %s%n",
                        t.get("id"), t.get("cron"), mode, store, ageHours, promptPreview));
            }
            return sb.toString().trim();
        }

        /**
         * 排空通知队列，获取所有已触发的通知。
         * <p>
         * 在 agentLoop 每次调用 LLM 之前调用，将触发的任务提示词注入对话。
         *
         * @return 通知列表
         */
        public List<String> drainNotifications() {
            List<String> notifications = new ArrayList<>();
            String notification;
            while ((notification = notificationQueue.poll()) != null) {
                notifications.add(notification);
            }
            return notifications;
        }

        /**
         * 启动时检测错过的持久化任务触发。
         * <p>
         * 遍历每个持久化任务，从上次触发时间到当前时间逐分钟检查，
         * 如果期间有应该触发但未触发的 cron 匹配，标记为错过。
         * 检查上限 24 小时，防止启动时消耗过多资源。
         *
         * @return 错过的任务信息列表
         */
        public List<Map<String, Object>> detectMissedTasks() {
            LocalDateTime now = LocalDateTime.now();
            List<Map<String, Object>> missed = new ArrayList<>();

            for (Map<String, Object> task : tasks) {
                Object lastFiredObj = task.get("last_fired");
                if (lastFiredObj == null) {
                    continue;
                }

                LocalDateTime lastDt;
                if (lastFiredObj instanceof Number) {
                    lastDt = LocalDateTime.ofEpochSecond(
                            ((Number) lastFiredObj).longValue(), 0,
                            java.time.ZoneOffset.of(java.time.ZoneId.systemDefault().getRules().getOffset(now).getId()));
                } else {
                    continue;
                }

                // 从上次触发时间的下一分钟开始，逐分钟检查（上限 24 小时）
                LocalDateTime check = lastDt.plusMinutes(1);
                LocalDateTime cap = now.isBefore(lastDt.plusHours(24)) ? now : lastDt.plusHours(24);

                while (!check.isAfter(cap)) {
                    if (cronMatches((String) task.get("cron"), check)) {
                        Map<String, Object> miss = new LinkedHashMap<>();
                        miss.put("id", task.get("id"));
                        miss.put("cron", task.get("cron"));
                        miss.put("prompt", task.get("prompt"));
                        miss.put("missed_at", check.toString());
                        missed.add(miss);
                        break; // 每个任务只需报告一次错过
                    }
                    check = check.plusMinutes(1);
                }
            }
            return missed;
        }

        // ---- 内部方法 ----

        /**
         * 计算抖动偏移：如果 cron 指向 :00 或 :30，返回 1-4 分钟偏移。
         * <p>
         * 偏移量基于表达式的哈希值确定性计算，同一表达式始终得到相同偏移。
         */
        private int computeJitter(String cronExpr) {
            String[] fields = cronExpr.trim().split("\\s+");
            if (fields.length < 1) {
                return 0;
            }
            String minuteField = fields[0];
            try {
                int minuteVal = Integer.parseInt(minuteField);
                if (JITTER_MINUTES.contains(minuteVal)) {
                    // 基于表达式的哈希确定偏移（1 到 JITTER_OFFSET_MAX）
                    return (Math.abs(cronExpr.hashCode()) % JITTER_OFFSET_MAX) + 1;
                }
            } catch (NumberFormatException ignored) {
                // 分钟字段不是简单数字（如 */5），不需要抖动
            }
            return 0;
        }

        /**
         * 后台检查循环：每秒检查一次，每分钟最多触发一次。
         * <p>
         * 通过 lastCheckMinute 确保同一分钟内不会重复触发。
         */
        private void checkLoop() {
            while (!stopRequested.get()) {
                LocalDateTime now = LocalDateTime.now();
                int currentMinute = now.getHour() * 60 + now.getMinute();

                // 每分钟只检查一次，避免重复触发
                if (currentMinute != lastCheckMinute.get()) {
                    lastCheckMinute.set(currentMinute);
                    checkTasks(now);
                }

                // 等待 1 秒（可被 stop 信号中断）
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        /**
         * 检查所有任务是否匹配当前时间，触发匹配的任务。
         * <p>
         * 处理流程：
         * 1. 检查 auto-expiry（recurring 任务超过 7 天自动过期）
         * 2. 应用抖动偏移后进行 cron 匹配
         * 3. 匹配的任务加入通知队列
         * 4. 清理过期和已触发的一次性任务
         */
        private void checkTasks(LocalDateTime now) {
            List<String> expired = new ArrayList<>();
            List<String> firedOneShots = new ArrayList<>();
            double nowEpoch = System.currentTimeMillis() / 1000.0;

            // 使用快照遍历，避免并发修改
            List<Map<String, Object>> snapshot;
            synchronized (tasks) {
                snapshot = new ArrayList<>(tasks);
            }

            for (Map<String, Object> task : snapshot) {
                // 自动过期检查：recurring 任务超过 7 天
                double ageDays = (nowEpoch - ((Number) task.get("createdAt")).doubleValue()) / 86400.0;
                if (Boolean.TRUE.equals(task.get("recurring")) && ageDays > AUTO_EXPIRY_DAYS) {
                    expired.add((String) task.get("id"));
                    continue;
                }

                // 应用抖动偏移：将检查时间向前推移 jitter_offset 分钟
                LocalDateTime checkTime = now;
                Object jitterObj = task.get("jitter_offset");
                if (jitterObj instanceof Number jitter && jitter.intValue() > 0) {
                    checkTime = now.minusMinutes(jitter.intValue());
                }

                // cron 匹配检查
                if (cronMatches((String) task.get("cron"), checkTime)) {
                    String notification = "[Scheduled task " + task.get("id") + "]: " + task.get("prompt");
                    notificationQueue.add(notification);
                    task.put("last_fired", nowEpoch);
                    System.out.println(yellow("[Cron] Fired: " + task.get("id")));

                    // 一次性任务触发后标记删除
                    if (!Boolean.TRUE.equals(task.get("recurring"))) {
                        firedOneShots.add((String) task.get("id"));
                    }
                }
            }

            // 清理过期和已完成的一次性任务
            if (!expired.isEmpty() || !firedOneShots.isEmpty()) {
                Set<String> removeIds = new HashSet<>(expired);
                removeIds.addAll(firedOneShots);
                tasks.removeIf(t -> removeIds.contains(t.get("id")));
                for (String tid : expired) {
                    System.out.println(yellow("[Cron] Auto-expired: " + tid + " (older than " + AUTO_EXPIRY_DAYS + " days)"));
                }
                for (String tid : firedOneShots) {
                    System.out.println(yellow("[Cron] One-shot completed and removed: " + tid));
                }
                saveDurable();
            }
        }

        /**
         * 从磁盘加载持久化任务。
         * <p>
         * 仅加载 durable=true 的任务。文件格式为 JSON 数组。
         */
        private void loadDurable() {
            if (!Files.exists(SCHEDULED_TASKS_FILE)) {
                return;
            }
            try {
                String json = Files.readString(SCHEDULED_TASKS_FILE);
                if (json.isBlank()) return;

                // 简易 JSON 数组解析（不引入外部 JSON 库）
                List<Map<String, Object>> loaded = parseSimpleJsonArray(json);
                for (Map<String, Object> item : loaded) {
                    if (Boolean.TRUE.equals(item.get("durable"))) {
                        tasks.add(item);
                    }
                }
            } catch (Exception e) {
                System.err.println("[Cron] Error loading tasks: " + e.getMessage());
            }
        }

        /**
         * 保存持久化任务到磁盘。
         * <p>
         * 仅保存 durable=true 的任务到 .claude/scheduled_tasks.json。
         */
        private void saveDurable() {
            try {
                List<Map<String, Object>> durable = new ArrayList<>();
                synchronized (tasks) {
                    for (Map<String, Object> t : tasks) {
                        if (Boolean.TRUE.equals(t.get("durable"))) {
                            durable.add(t);
                        }
                    }
                }
                Files.createDirectories(SCHEDULED_TASKS_FILE.getParent());
                Files.writeString(SCHEDULED_TASKS_FILE, toJsonArray(durable) + "\n");
            } catch (Exception e) {
                System.err.println("[Cron] Error saving tasks: " + e.getMessage());
            }
        }
    }

    // ==================== 简易 JSON 序列化/反序列化 ====================
    // 不引入外部 JSON 库，用于持久化任务的读写

    /**
     * 将任务列表序列化为 JSON 数组字符串。
     * <p>
     * 仅处理任务中出现的已知字段类型：String、Number、Boolean。
     * 字段名硬编码以确保一致性。
     */
    private static String toJsonArray(List<Map<String, Object>> items) {
        if (items.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> m = items.get(i);
            sb.append("  {\n");
            sb.append("    \"id\": ").append(jsonVal(m.get("id"))).append(",\n");
            sb.append("    \"cron\": ").append(jsonVal(m.get("cron"))).append(",\n");
            sb.append("    \"prompt\": ").append(jsonVal(m.get("prompt"))).append(",\n");
            sb.append("    \"recurring\": ").append(m.get("recurring")).append(",\n");
            sb.append("    \"durable\": ").append(m.get("durable")).append(",\n");
            sb.append("    \"createdAt\": ").append(m.get("createdAt")).append(",\n");
            if (m.get("jitter_offset") != null) {
                sb.append("    \"jitter_offset\": ").append(m.get("jitter_offset")).append(",\n");
            }
            if (m.get("last_fired") != null) {
                sb.append("    \"last_fired\": ").append(m.get("last_fired")).append(",\n");
            }
            // 移除末尾多余的逗号
            int lastComma = sb.lastIndexOf(",\n");
            if (lastComma > sb.lastIndexOf("{\n") + 2) {
                sb.delete(lastComma, lastComma + 1);
            }
            sb.append("  }");
            if (i < items.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    /** 将值转为 JSON 字符串表示（字符串加引号，数字/布尔原样输出） */
    private static String jsonVal(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "\"" + escapeJson((String) v) + "\"";
        return v.toString();
    }

    /** JSON 字符串转义：处理引号、反斜杠、换行等特殊字符 */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 简易 JSON 数组解析器。
     * <p>
     * 仅支持本场景的数据格式：一个对象数组，每个对象含 String/Number/Boolean 字段。
     * 不支持嵌套对象或数组值。
     */
    private static List<Map<String, Object>> parseSimpleJsonArray(String json) {
        List<Map<String, Object>> result = new ArrayList<>();
        String trimmed = json.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return result;

        // 去掉外层 [ ]
        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return result;

        // 按顶层 { } 分割对象
        int depth = 0;
        int start = -1;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String objStr = inner.substring(start, i + 1);
                    result.add(parseSimpleJsonObject(objStr));
                    start = -1;
                }
            }
        }
        return result;
    }

    /**
     * 简易 JSON 对象解析器。
     * <p>
     * 将 {"key": value, ...} 解析为 Map。
     * 值类型：字符串（带引号）、数字、布尔。
     */
    private static Map<String, Object> parseSimpleJsonObject(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        String inner = json.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}")) inner = inner.substring(0, inner.length() - 1);
        inner = inner.trim();

        // 逐个解析 "key": value 对
        int i = 0;
        while (i < inner.length()) {
            // 跳过空白和逗号
            while (i < inner.length() && (inner.charAt(i) == ' ' || inner.charAt(i) == ','
                    || inner.charAt(i) == '\n' || inner.charAt(i) == '\r' || inner.charAt(i) == '\t')) {
                i++;
            }
            if (i >= inner.length()) break;

            // 期望 key（带引号的字符串）
            if (inner.charAt(i) != '"') break;
            int keyStart = i + 1;
            int keyEnd = findClosingQuote(inner, keyStart);
            String key = unescapeJson(inner.substring(keyStart, keyEnd));
            i = keyEnd + 1;

            // 跳过空白和冒号
            while (i < inner.length() && (inner.charAt(i) == ' ' || inner.charAt(i) == ':'
                    || inner.charAt(i) == '\n' || inner.charAt(i) == '\r' || inner.charAt(i) == '\t')) {
                i++;
            }
            if (i >= inner.length()) break;

            // 解析 value
            char vc = inner.charAt(i);
            if (vc == '"') {
                // 字符串值
                int valStart = i + 1;
                int valEnd = findClosingQuote(inner, valStart);
                map.put(key, unescapeJson(inner.substring(valStart, valEnd)));
                i = valEnd + 1;
            } else if (vc == 't' || vc == 'f') {
                // 布尔值
                if (inner.startsWith("true", i)) {
                    map.put(key, true);
                    i += 4;
                } else if (inner.startsWith("false", i)) {
                    map.put(key, false);
                    i += 5;
                }
            } else if (vc == 'n') {
                // null
                map.put(key, null);
                i += 4;
            } else if (vc == '-' || Character.isDigit(vc)) {
                // 数字值
                int numStart = i;
                while (i < inner.length() && (Character.isDigit(inner.charAt(i))
                        || inner.charAt(i) == '.' || inner.charAt(i) == '-'
                        || inner.charAt(i) == 'e' || inner.charAt(i) == 'E' || inner.charAt(i) == '+')) {
                    i++;
                }
                String numStr = inner.substring(numStart, i);
                if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                    map.put(key, Double.parseDouble(numStr));
                } else {
                    map.put(key, Long.parseLong(numStr));
                }
            }
        }
        return map;
    }

    /** 找到字符串中下一个未转义的引号位置 */
    private static int findClosingQuote(String s, int start) {
        int i = start;
        while (i < s.length()) {
            if (s.charAt(i) == '\\' && i + 1 < s.length()) {
                i += 2; // 跳过转义字符
            } else if (s.charAt(i) == '"') {
                return i;
            } else {
                i++;
            }
        }
        return s.length();
    }

    /** JSON 字符串反转义 */
    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"'  -> { sb.append('"');  i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case 'n'  -> { sb.append('\n'); i++; }
                    case 'r'  -> { sb.append('\r'); i++; }
                    case 't'  -> { sb.append('\t'); i++; }
                    default   -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ==================== JsonValue 转换 ====================

    /**
     * 将 SDK 的 JsonValue 转换为普通 Java 对象。
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
     * Agent 核心循环（带 Cron 通知排空）。
     * <p>
     * 与 S02 的 agentLoop 几乎相同，唯一区别：
     * 在每次 LLM 调用之前，先排空调度器的通知队列，
     * 将触发的任务提示词作为 user 消息注入对话。
     * <p>
     * 这就是 Agent "醒来" 处理定时工作的方式。
     * <pre>
     *   while (stopReason == TOOL_USE) {
     *       drain notifications;        // <-- 新增：排空调度通知
     *       inject as user messages;    // <-- 新增：注入对话
     *       response = LLM(messages, tools);
     *       execute tools;
     *       append results;
     *   }
     * </pre>
     *
     * @param client        Anthropic API 客户端
     * @param model         模型 ID
     * @param paramsBuilder 消息创建参数构建器
     * @param tools         工具定义列表
     * @param toolHandlers  工具分发表
     * @param scheduler     Cron 调度器实例
     */
    @SuppressWarnings("unchecked")
    private static void agentLoop(AnthropicClient client, String model,
                                  MessageCreateParams.Builder paramsBuilder,
                                  List<Tool> tools,
                                  Map<String, Function<Map<String, Object>, String>> toolHandlers,
                                  CronScheduler scheduler) {
        while (true) {
            // ---- 0. 排空调度通知并注入对话 ----
            List<String> notifications = scheduler.drainNotifications();
            for (String note : notifications) {
                System.out.println(yellow("[Cron notification] " + note.substring(0, Math.min(note.length(), 100))));
                paramsBuilder.addUserMessage(note);
            }

            // ---- 1. 调用 LLM ----
            Message response = client.messages().create(paramsBuilder.build());

            // ---- 2. 将 assistant 回复追加到历史 ----
            paramsBuilder.addMessage(response);

            // ---- 3. 检查是否需要继续执行工具 ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
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

                    Function<Map<String, Object>, String> handler = toolHandlers.get(toolName);
                    String output;
                    if (handler != null) {
                        output = handler.apply(input);
                    } else {
                        output = "Unknown tool: " + toolName;
                    }

                    System.out.println(bold("> " + toolName) + ":");
                    System.out.println(dim("  " + output.substring(0, Math.min(output.length(), 200))));

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
     * REPL 主循环。
     * <p>
     * 整体流程：
     * <pre>
     * scheduler.start()                  // 启动调度器
     * while True:
     *     query = input("s14 >> ")       // 读取用户输入
     *     if /cron → list tasks          // REPL 命令：查看任务
     *     if /test → enqueue test note   // REPL 命令：测试通知
     *     messages.append(query)         // 追加到历史
     *     agent_loop(messages)           // 执行 Agent 循环（含通知排空）
     * </pre>
     */
    public static void main(String[] args) {
        // ---- 构建客户端和加载模型 ----
        AnthropicClient client = buildClient();
        String model = loadModel();

        // ---- 创建并启动 Cron 调度器 ----
        CronScheduler scheduler = new CronScheduler();
        scheduler.start();
        System.out.println("[Cron scheduler running. Background checks every second.]");

        // ---- 定义 7 个工具 ----
        List<Tool> tools = List.of(
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
                        List.of("path", "old_text", "new_text")),

                // cron_create：创建定时任务
                defineTool("cron_create",
                        "Schedule a recurring or one-shot task with a cron expression.",
                        Map.of(
                                "cron", Map.of("type", "string",
                                        "description", "5-field cron expression: 'min hour dom month dow'"),
                                "prompt", Map.of("type", "string",
                                        "description", "The prompt to inject when the task fires"),
                                "recurring", Map.of("type", "boolean",
                                        "description", "true=repeat, false=fire once then delete. Default true."),
                                "durable", Map.of("type", "boolean",
                                        "description", "true=persist to disk, false=session-only. Default false.")),
                        List.of("cron", "prompt")),

                // cron_delete：删除定时任务
                defineTool("cron_delete", "Delete a scheduled task by ID.",
                        Map.of(
                                "id", Map.of("type", "string",
                                        "description", "Task ID to delete")),
                        List.of("id")),

                // cron_list：列出所有定时任务
                defineTool("cron_list", "List all scheduled tasks.",
                        Map.of(),
                        null)
        );

        // ---- 工具分发表 ----
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
        toolHandlers.put("cron_create", input -> {
            String cron = (String) input.get("cron");
            String prompt = (String) input.get("prompt");
            if (cron == null || cron.isBlank()) return "Error: cron is required";
            if (prompt == null || prompt.isBlank()) return "Error: prompt is required";
            boolean recurring = true;
            Object recurringObj = input.get("recurring");
            if (recurringObj instanceof Boolean b) recurring = b;
            boolean durable = false;
            Object durableObj = input.get("durable");
            if (durableObj instanceof Boolean b) durable = b;
            return scheduler.create(cron, prompt, recurring, durable);
        });
        toolHandlers.put("cron_delete", input -> {
            String id = (String) input.get("id");
            if (id == null || id.isBlank()) return "Error: id is required";
            return scheduler.delete(id);
        });
        toolHandlers.put("cron_list", input -> scheduler.listTasks());

        // ---- 构建消息参数 ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000L)
                .system(SYSTEM_PROMPT);

        for (Tool tool : tools) {
            paramsBuilder.addTool(tool);
        }

        // ---- REPL 主循环 ----
        Scanner scanner = new Scanner(System.in);
        System.out.println(bold("S14 Cron Scheduler") + " — 7 tools: bash, read_file, write_file, edit_file, cron_create, cron_delete, cron_list");
        System.out.println("[Commands: /cron to list tasks, /test to fire a test notification]");
        System.out.println("Type 'q' or 'exit' to quit.\n");

        while (true) {
            System.out.print(cyan("s14 >> "));
            if (!scanner.hasNextLine()) {
                scheduler.stop();
                break;
            }

            String query = scanner.nextLine().trim();

            // 空输入或退出命令 → 停止调度器并退出
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                scheduler.stop();
                break;
            }

            // /cron：列出所有调度任务
            if ("/cron".equals(query)) {
                System.out.println(scheduler.listTasks());
                continue;
            }

            // /test：手动注入测试通知
            if ("/test".equals(query)) {
                scheduler.notificationQueue.add("[Scheduled task test-0000]: This is a test notification.");
                System.out.println("[Test notification enqueued. It will be injected on your next message.]");
                continue;
            }

            // 追加用户消息到对话历史
            paramsBuilder.addUserMessage(query);

            // 执行 Agent 循环（LLM 调用 + 工具执行 + 通知排空）
            try {
                agentLoop(client, model, paramsBuilder, tools, toolHandlers, scheduler);
            } catch (Exception e) {
                System.err.println(red("Error: " + e.getMessage()));
            }
            System.out.println();
        }

        System.out.println(dim("Bye!"));
    }
}
