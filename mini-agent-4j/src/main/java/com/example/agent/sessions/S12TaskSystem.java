package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.cdimascio.dotenv.Dotenv;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * S12：任务系统 —— 持久化到文件系统的目标，不怕上下文压缩（自包含实现）。
 * <p>
 * 任务以 JSON 文件存储在 .tasks/ 目录，具备依赖图（blockedBy/blocks）。
 * 完成任务时自动解除其他任务的依赖。
 * <p>
 * 核心洞察："状态存在对话之外——因为它在文件系统上。"
 * 任务记录是持久化的工作项，不是线程、后台槽位或工作进程。
 * <p>
 * 依赖解析：
 * <pre>
 *   task_1.json  {"id":1, "status":"completed", ...}
 *   task_2.json  {"id":2, "blockedBy":[1], "status":"pending", ...}
 *   task_3.json  {"id":3, "blockedBy":[2], "status":"pending", ...}
 *
 *   完成任务 1 时 → 自动从任务 2 的 blockedBy 中移除
 * </pre>
 * <p>
 * 整个文件完全自包含——不依赖 com.example.agent.* 下的任何类。
 * 对应 Python 原版：s12_task_system.py
 */
public class S12TaskSystem {

    // ==================== 常量定义 ====================

    private static final Path WORKDIR = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    private static final long MAX_TOKENS = 8000;
    private static final int BASH_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT = 50000;
    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_CYAN = "\033[36m";
    private static final String ANSI_DIM = "\033[2m";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path TASKS_DIR = WORKDIR.resolve(".tasks");

    // ==================== 主入口 ====================

    public static void main(String[] args) {
        // 加载环境变量
        Dotenv dotenv = Dotenv.configure().directory(WORKDIR.toString()).ignoreIfMissing().load();
        String baseUrl = dotenv.get("ANTHROPIC_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) System.clearProperty("ANTHROPIC_AUTH_TOKEN");

        String apiKey = dotenv.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("ANTHROPIC_API_KEY 未配置");
        String model = dotenv.get("MODEL_ID");
        if (model == null || model.isBlank()) throw new IllegalStateException("MODEL_ID 未配置");

        AnthropicClient client = buildClient(apiKey, baseUrl);

        // 确保 .tasks/ 目录存在
        try { Files.createDirectories(TASKS_DIR); } catch (IOException ignored) {}

        // ---- TaskManager 初始化 ----
        TaskManager taskMgr = new TaskManager();

        String systemPrompt = "You are a coding agent at " + WORKDIR
                + ". Use task tools to plan and track work.";

        // ---- 工具定义（8 个） ----
        List<Tool> tools = List.of(
                defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
                defineTool("read_file", "Read file contents.",
                        Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer")),
                        List.of("path")),
                defineTool("write_file", "Write content to file.",
                        Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")),
                        List.of("path", "content")),
                defineTool("edit_file", "Replace exact text in file.",
                        Map.of("path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),
                defineTool("task_create", "Create a new task.",
                        Map.of("subject", Map.of("type", "string"),
                                "description", Map.of("type", "string")),
                        List.of("subject")),
                defineTool("task_get", "Get full details of a task by ID.",
                        Map.of("task_id", Map.of("type", "integer")),
                        List.of("task_id")),
                defineTool("task_update", "Update a task's status or dependencies.",
                        Map.of("task_id", Map.of("type", "integer"),
                                "status", Map.of("type", "string",
                                        "enum", List.of("pending", "in_progress", "completed", "deleted")),
                                "addBlockedBy", Map.of("type", "array",
                                        "items", Map.of("type", "integer")),
                                "addBlocks", Map.of("type", "array",
                                        "items", Map.of("type", "integer"))),
                        List.of("task_id")),
                defineTool("task_list", "List all tasks with status summary.",
                        Map.of(), null)
        );

        // ---- 工具分发 Map ----
        Map<String, java.util.function.Function<Map<String, Object>, String>> handlers = new LinkedHashMap<>();
        handlers.put("bash", input -> runBash((String) input.get("command")));
        handlers.put("read_file", input -> runRead((String) input.get("path"),
                input.get("limit") instanceof Number n ? n.intValue() : null));
        handlers.put("write_file", input -> runWrite((String) input.get("path"), (String) input.get("content")));
        handlers.put("edit_file", input -> runEdit((String) input.get("path"),
                (String) input.get("old_text"), (String) input.get("new_text")));
        handlers.put("task_create", input -> taskMgr.create(
                (String) input.get("subject"), (String) input.getOrDefault("description", "")));
        handlers.put("task_get", input -> taskMgr.get(((Number) input.get("task_id")).intValue()));
        handlers.put("task_update", input -> {
            @SuppressWarnings("unchecked")
            List<Integer> blockedBy = (List<Integer>) input.get("addBlockedBy");
            @SuppressWarnings("unchecked")
            List<Integer> blocks = (List<Integer>) input.get("addBlocks");
            return taskMgr.update(((Number) input.get("task_id")).intValue(),
                    (String) input.get("status"), blockedBy, blocks);
        });
        handlers.put("task_list", input -> taskMgr.listAll());

        // ---- REPL ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model).maxTokens(MAX_TOKENS).system(systemPrompt);
        for (Tool tool : tools) paramsBuilder.addTool(tool);

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print(ansiCyan("s12 >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            paramsBuilder.addUserMessage(query);
            try {
                agentLoop(client, paramsBuilder, handlers);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
        System.out.println("Bye!");
    }

    // ==================== Agent 核心循环 ====================

    private static void agentLoop(AnthropicClient client,
                                  MessageCreateParams.Builder paramsBuilder,
                                  Map<String, java.util.function.Function<Map<String, Object>, String>> handlers) {
        while (true) {
            Message response = client.messages().create(paramsBuilder.build());
            paramsBuilder.addMessage(response);

            boolean isToolUse = response.stopReason()
                    .map(StopReason.TOOL_USE::equals).orElse(false);
            if (!isToolUse) {
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(tb -> System.out.println(tb.text()));
                }
                return;
            }

            List<ContentBlockParam> toolResults = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());
                    if (input == null) input = Map.of();

                    String name = toolUse.name();
                    String output;
                    var handler = handlers.get(name);
                    if (handler != null) {
                        try { output = handler.apply(input); }
                        catch (Exception e) { output = "Error: " + e.getMessage(); }
                    } else {
                        output = "Unknown tool: " + name;
                    }

                    System.out.println(ANSI_DIM + "> " + name + ": "
                            + output.substring(0, Math.min(200, output.length())) + ANSI_RESET);

                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                }
            }
            if (!toolResults.isEmpty()) {
                paramsBuilder.addUserMessageOfBlockParams(toolResults);
            }
        }
    }

    // ==================== TaskManager 内部类 ====================

    /**
     * 任务管理器 —— 持久化到 .tasks/ 目录。
     * <p>
     * 每个任务是一个 JSON 文件，包含：
     * - id: 自增整数
     * - subject: 任务标题
     * - description: 详细描述
     * - status: pending / in_progress / completed / deleted
     * - owner: 认领者
     * - blockedBy: 阻塞依赖列表
     * - blocks: 被阻塞列表
     * <p>
     * 完成任务时自动解除其他任务的 blockedBy 依赖。
     */
    static class TaskManager {
        int nextId() {
            try (var stream = Files.list(TASKS_DIR)) {
                return stream
                        .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                        .mapToInt(p -> {
                            String n = p.getFileName().toString();
                            return Integer.parseInt(n.substring(5, n.length() - 5));
                        }).max().orElse(0) + 1;
            } catch (IOException e) { return 1; }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> load(int taskId) {
            Path p = TASKS_DIR.resolve("task_" + taskId + ".json");
            if (!Files.exists(p)) throw new IllegalArgumentException("Task " + taskId + " not found");
            try { return MAPPER.readValue(Files.readString(p), Map.class); }
            catch (IOException e) { throw new RuntimeException(e); }
        }

        void save(Map<String, Object> task) {
            int id = ((Number) task.get("id")).intValue();
            try {
                Files.writeString(TASKS_DIR.resolve("task_" + id + ".json"),
                        MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task));
            } catch (IOException e) { throw new RuntimeException(e); }
        }

        String create(String subject, String description) {
            int id = nextId();
            var task = new LinkedHashMap<String, Object>();
            task.put("id", id);
            task.put("subject", subject);
            task.put("description", description != null ? description : "");
            task.put("status", "pending");
            task.put("owner", null);
            task.put("blockedBy", new ArrayList<Integer>());
            task.put("blocks", new ArrayList<Integer>());
            save(task);
            return toJson(task);
        }

        String get(int taskId) {
            try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(load(taskId)); }
            catch (IOException e) { return "Error: " + e.getMessage(); }
        }

        /**
         * 更新任务。完成时自动解除依赖。
         */
        @SuppressWarnings("unchecked")
        String update(int taskId, String status, List<Integer> addBlockedBy, List<Integer> addBlocks) {
            var task = load(taskId);
            if (status != null) {
                task.put("status", status);
                // 完成任务时，自动从其他任务的 blockedBy 中移除此任务
                if ("completed".equals(status)) {
                    try (var stream = Files.list(TASKS_DIR)) {
                        stream.filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                                .forEach(p -> {
                                    try {
                                        var t = (Map<String, Object>) MAPPER.readValue(Files.readString(p), Map.class);
                                        List<Integer> bb = (List<Integer>) t.getOrDefault("blockedBy", new ArrayList<>());
                                        if (bb.remove(Integer.valueOf(taskId))) save(t);
                                    } catch (IOException ignored) {}
                                });
                    } catch (IOException ignored) {}
                }
                // 删除任务
                if ("deleted".equals(status)) {
                    try { Files.deleteIfExists(TASKS_DIR.resolve("task_" + taskId + ".json")); }
                    catch (IOException ignored) {}
                    return "Task " + taskId + " deleted";
                }
            }
            if (addBlockedBy != null) {
                List<Integer> bb = (List<Integer>) task.getOrDefault("blockedBy", new ArrayList<>());
                bb.addAll(addBlockedBy);
                // 去重：LinkedHashSet 保持插入顺序的同时去除重复依赖
                task.put("blockedBy", new ArrayList<>(new LinkedHashSet<>(bb)));

                // ---- 双向 DAG 维护：正向边（blockedBy）→ 反向边（blocks）----
                // 当前任务声明 "我被 blockerId 阻塞" 时，
                // 需要同步在 blockerId 的 "blocks" 列表中添加当前任务，
                // 这样完成任务时只需查 blocker 的 blocks 列表就知道该解锁谁。
                for (int blockerId : addBlockedBy) {
                    try {
                        var blocker = load(blockerId);
                        List<Integer> blockerBlocks = (List<Integer>) blocker.getOrDefault("blocks", new ArrayList<>());
                        if (!blockerBlocks.contains(taskId)) {
                            blockerBlocks.add(taskId);
                            blocker.put("blocks", new ArrayList<>(new LinkedHashSet<>(blockerBlocks)));
                            save(blocker);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 依赖的阻塞任务不存在，跳过（允许悬空依赖）
                    }
                }
            }
            if (addBlocks != null) {
                List<Integer> bl = (List<Integer>) task.getOrDefault("blocks", new ArrayList<>());
                bl.addAll(addBlocks);
                task.put("blocks", new ArrayList<>(new LinkedHashSet<>(bl)));

                // ---- 双向 DAG 维护：反向边（blocks）→ 正向边（blockedBy）----
                // 当前任务声明 "我阻塞了 blockedId" 时，
                // 需要同步在 blockedId 的 "blockedBy" 列表中添加当前任务。
                // 与上面的正向→反向逻辑是对称的，保证无论从哪一边建立依赖都能双向一致。
                for (int blockedId : addBlocks) {
                    try {
                        var blocked = load(blockedId);
                        List<Integer> blockedBy = (List<Integer>) blocked.getOrDefault("blockedBy", new ArrayList<>());
                        if (!blockedBy.contains(taskId)) {
                            blockedBy.add(taskId);
                            blocked.put("blockedBy", new ArrayList<>(new LinkedHashSet<>(blockedBy)));
                            save(blocked);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // 被阻塞的任务不存在，跳过
                    }
                }
            }
            save(task);
            return toJson(task);
        }

        @SuppressWarnings("unchecked")
        String listAll() {
            try (var stream = Files.list(TASKS_DIR)) {
                var tasks = stream
                        .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                        .sorted().map(p -> {
                            try { return (Map<String, Object>) MAPPER.readValue(Files.readString(p), Map.class); }
                            catch (IOException e) { return null; }
                        }).filter(Objects::nonNull).toList();

                if (tasks.isEmpty()) return "No tasks.";
                var lines = new ArrayList<String>();
                for (var t : tasks) {
                    String s = (String) t.getOrDefault("status", "?");
                    String m = switch (s) {
                        case "pending" -> "[ ]";
                        case "in_progress" -> "[>]";
                        case "completed" -> "[x]";
                        default -> "[?]";
                    };
                    String owner = t.get("owner") != null ? " @" + t.get("owner") : "";
                    @SuppressWarnings("unchecked")
                    List<Integer> bb = (List<Integer>) t.getOrDefault("blockedBy", List.of());
                    String blocked = bb.isEmpty() ? "" : " (blocked by: " + bb + ")";
                    lines.add(m + " #" + t.get("id") + ": " + t.get("subject") + owner + blocked);
                }
                return String.join("\n", lines);
            } catch (IOException e) { return "Error: " + e.getMessage(); }
        }

        String toJson(Map<String, Object> obj) {
            try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(obj); }
            catch (IOException e) { return obj.toString(); }
        }
    }

    // ==================== 基础设施 ====================

    private static AnthropicClient buildClient(String apiKey, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return AnthropicOkHttpClient.builder().apiKey(apiKey).baseUrl(baseUrl).build();
        }
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    private static Tool defineTool(String name, String description,
                                   Map<String, Object> properties, List<String> required) {
        var schemaBuilder = Tool.InputSchema.builder().properties(JsonValue.from(properties));
        if (required != null && !required.isEmpty()) {
            schemaBuilder.putAdditionalProperty("required", JsonValue.from(required));
        }
        return Tool.builder().name(name).description(description)
                .inputSchema(schemaBuilder.build()).build();
    }

    private static Path safePath(String p) {
        Path resolved = WORKDIR.resolve(p).normalize().toAbsolutePath();
        if (!resolved.startsWith(WORKDIR)) throw new IllegalArgumentException("Path escapes workspace: " + p);
        return resolved;
    }

    private static String runBash(String command) {
        if (command == null || command.isBlank()) return "Error: command is required";
        for (String d : List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/")) {
            if (command.contains(d)) return "Error: Dangerous command blocked";
        }
        try {
            ProcessBuilder pb = System.getProperty("os.name").toLowerCase().contains("win")
                    ? new ProcessBuilder("cmd", "/c", command)
                    : new ProcessBuilder("bash", "-c", command);
            pb.directory(WORKDIR.toFile()).redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (!process.waitFor(BASH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "Error: Timeout (" + BASH_TIMEOUT_SECONDS + "s)";
            }
            if (output.isEmpty()) return "(no output)";
            return output.length() > MAX_OUTPUT ? output.substring(0, MAX_OUTPUT) : output;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runRead(String path, Integer limit) {
        try {
            var lines = safePath(path).toFile().exists()
                    ? Files.readAllLines(safePath(path)) : List.<String>of();
            if (limit != null && limit < lines.size()) {
                lines = new ArrayList<>(lines.subList(0, limit));
                lines.add("... (" + (lines.size() - limit) + " more)");
            }
            String result = String.join("\n", lines);
            return result.length() > MAX_OUTPUT ? result.substring(0, MAX_OUTPUT) : result;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runWrite(String path, String content) {
        try {
            Path fp = safePath(path);
            Files.createDirectories(fp.getParent());
            Files.writeString(fp, content);
            return "Wrote " + content.length() + " bytes to " + path;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runEdit(String path, String oldText, String newText) {
        try {
            Path fp = safePath(path);
            String content = Files.readString(fp);
            if (!content.contains(oldText)) return "Error: Text not found in " + path;
            Files.writeString(fp, content.substring(0, content.indexOf(oldText)) + newText + content.substring(content.indexOf(oldText) + oldText.length()));
            return "Edited " + path;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

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
                for (var entry : raw.entrySet()) result.put(entry.getKey(), jsonValueToObject(entry.getValue()));
                return result;
            }
        } catch (ClassCastException ignored) {}
        try {
            var listOpt = value.asArray();
            if (listOpt.isPresent()) {
                List<JsonValue> raw = (List<JsonValue>) (Object) listOpt.get();
                List<Object> result = new ArrayList<>();
                for (JsonValue item : raw) result.add(jsonValueToObject(item));
                return result;
            }
        } catch (ClassCastException ignored) {}
        return null;
    }

    private static String ansiCyan(String text) { return ANSI_CYAN + text + ANSI_RESET; }
}
