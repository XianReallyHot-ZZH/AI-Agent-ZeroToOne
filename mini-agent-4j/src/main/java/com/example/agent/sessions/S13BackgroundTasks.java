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
import java.util.concurrent.*;

/**
 * S13：后台任务 —— 模型思考时，Harness 等待（自包含实现）。
 * <p>
 * 长时间运行的命令在后台线程中执行。
 * 完成通知通过队列传递，在下一次 LLM 调用前批量注入。
 * <p>
 * 时间线：
 * <pre>
 * Agent ----[spawn A]----[spawn B]----[other work]----
 *              |              |
 *              v              v
 *           [A runs]      [B runs]        (parallel)
 *              |              |
 *              +-- notification queue --> [results injected]
 * </pre>
 * <p>
 * 核心洞察："发出即忘——Agent 不会因等待命令而阻塞。"
 * <p>
 * 后台任务是运行时执行槽位，不是 s12 中引入的持久化任务板记录。
 * <p>
 * 整个文件完全自包含。对应 Python 原版：s13_background_tasks.py
 */
public class S13BackgroundTasks {

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

    // ==================== 主入口 ====================

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure().directory(WORKDIR.toString()).ignoreIfMissing().load();
        String baseUrl = dotenv.get("ANTHROPIC_BASE_URL");
        if (baseUrl != null && !baseUrl.isBlank()) System.clearProperty("ANTHROPIC_AUTH_TOKEN");
        String apiKey = dotenv.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("ANTHROPIC_API_KEY 未配置");
        String model = dotenv.get("MODEL_ID");
        if (model == null || model.isBlank()) throw new IllegalStateException("MODEL_ID 未配置");

        AnthropicClient client = buildClient(apiKey, baseUrl);
        BackgroundManager bg = new BackgroundManager();

        String systemPrompt = "You are a coding agent at " + WORKDIR
                + ". Use background_run for long-running commands.";

        // ---- 工具定义（6 个） ----
        List<Tool> tools = List.of(
                defineTool("bash", "Run a shell command (blocking).",
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
                defineTool("background_run",
                        "Run command in background thread. Returns task_id immediately.",
                        Map.of("command", Map.of("type", "string")),
                        List.of("command")),
                defineTool("check_background",
                        "Check background task status. Omit task_id to list all.",
                        Map.of("task_id", Map.of("type", "string")), null)
        );

        // ---- 工具分发 ----
        Map<String, java.util.function.Function<Map<String, Object>, String>> handlers = new LinkedHashMap<>();
        handlers.put("bash", input -> runBash((String) input.get("command")));
        handlers.put("read_file", input -> runRead((String) input.get("path"),
                input.get("limit") instanceof Number n ? n.intValue() : null));
        handlers.put("write_file", input -> runWrite((String) input.get("path"), (String) input.get("content")));
        handlers.put("edit_file", input -> runEdit((String) input.get("path"),
                (String) input.get("old_text"), (String) input.get("new_text")));
        handlers.put("background_run", input -> bg.run((String) input.get("command")));
        handlers.put("check_background", input -> bg.check((String) input.get("task_id")));

        // ---- REPL ----
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model).maxTokens(MAX_TOKENS).system(systemPrompt);
        for (Tool tool : tools) paramsBuilder.addTool(tool);

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print(ansiCyan("s13 >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            paramsBuilder.addUserMessage(query);
            try {
                agentLoop(client, paramsBuilder, handlers, bg);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
        System.out.println("Bye!");
    }

    // ==================== Agent 核心循环（带后台通知注入） ====================

    /**
     * 带 drain 后台通知的 Agent 循环。
     * <p>
     * 在每次 LLM 调用前，drain 后台通知队列并注入对话历史。
     * 这样 LLM 能看到后台任务的完成结果。
     */
    private static void agentLoop(AnthropicClient client,
                                  MessageCreateParams.Builder paramsBuilder,
                                  Map<String, java.util.function.Function<Map<String, Object>, String>> handlers,
                                  BackgroundManager bg) {
        while (true) {
            // ---- S13 核心：drain 后台通知并注入对话历史 ----
            var notifs = bg.drain();
            if (!notifs.isEmpty()) {
                var notifLines = new ArrayList<String>();
                for (var n : notifs) {
                    notifLines.add("[bg:" + n.get("task_id") + "] " + n.get("status")
                            + ": " + n.get("preview")
                            + " (output_file=" + n.get("output_file") + ")");
                }
                String notifText = "<background-results>\n"
                        + String.join("\n", notifLines) + "\n</background-results>";
                System.out.println(ANSI_YELLOW + String.join("\n", notifLines) + ANSI_RESET);
                paramsBuilder.addUserMessage(notifText);
            }

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
                                    .toolUseId(toolUse.id()).content(output).build()));
                }
            }
            if (!toolResults.isEmpty()) {
                paramsBuilder.addUserMessageOfBlockParams(toolResults);
            }
        }
    }

    // ==================== BackgroundManager 内部类 ====================

    /**
     * 后台任务管理器 —— 运行时执行槽位（非持久化）。
     * <p>
     * 每个后台任务在独立线程中执行，完成后通过通知队列报告结果。
     * 主循环在每次 LLM 调用前 drain 队列，将结果注入对话。
     */
    static class BackgroundManager {
        private static final long STALL_THRESHOLD = 45_000;
        private static final Path RUNTIME_DIR = WORKDIR.resolve(".runtime-tasks");
        private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();
        private final LinkedBlockingQueue<Map<String, String>> notifications = new LinkedBlockingQueue<>();

        BackgroundManager() {
            try { Files.createDirectories(RUNTIME_DIR); } catch (IOException ignored) {}
        }

        private Path recordPath(String taskId) { return RUNTIME_DIR.resolve(taskId + ".json"); }
        private Path outputPath(String taskId) { return RUNTIME_DIR.resolve(taskId + ".log"); }

        @SuppressWarnings("unchecked")
        private void persistTask(String taskId) {
            try {
                var record = new LinkedHashMap<>(tasks.get(taskId));
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(recordPath(taskId).toFile(), record);
            } catch (IOException ignored) {}
        }

        private String preview(String output, int limit) {
            String compact = String.join(" ", (output == null || output.isEmpty()
                    ? "(no output)" : output).split("\\s+"));
            return compact.substring(0, Math.min(limit, compact.length()));
        }

        String run(String command) {
            String taskId = UUID.randomUUID().toString().substring(0, 8);
            Path outputFile = outputPath(taskId);
            var task = new LinkedHashMap<String, Object>();
            task.put("id", taskId);
            task.put("status", "running");
            task.put("result", null);
            task.put("command", command);
            task.put("started_at", System.currentTimeMillis());
            task.put("finished_at", null);
            task.put("result_preview", "");
            task.put("output_file", WORKDIR.relativize(outputFile).toString());
            tasks.put(taskId, task);
            persistTask(taskId);
            Thread.ofVirtual().name("bg-" + taskId).start(() -> exec(taskId, command));
            return "Background task " + taskId + " started: "
                    + command.substring(0, Math.min(80, command.length()))
                    + " (output_file=" + WORKDIR.relativize(outputFile) + ")";
        }

        private void exec(String taskId, String command) {
            String output;
            String status;
            try {
                ProcessBuilder pb = System.getProperty("os.name").toLowerCase().contains("win")
                        ? new ProcessBuilder("cmd", "/c", command)
                        : new ProcessBuilder("bash", "-c", command);
                pb.directory(WORKDIR.toFile()).redirectErrorStream(true);
                Process p = pb.start();
                String raw = new String(p.getInputStream().readAllBytes()).trim();
                if (!p.waitFor(300, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    raw = "Error: Timeout (300s)";
                    status = "error";
                } else {
                    status = "completed";
                }
                output = raw.isEmpty() ? "(no output)" : (raw.length() > MAX_OUTPUT ? raw.substring(0, MAX_OUTPUT) : raw);
            } catch (Exception e) {
                output = "Error: " + e.getMessage();
                status = "error";
            }
            String prev = preview(output, 500);
            try { Files.writeString(outputPath(taskId), output); } catch (IOException ignored) {}
            var task = tasks.get(taskId);
            task.put("status", status);
            task.put("result", output);
            task.put("finished_at", System.currentTimeMillis());
            task.put("result_preview", prev);
            persistTask(taskId);
            notifications.offer(Map.of(
                    "task_id", taskId,
                    "status", status,
                    "command", command.substring(0, Math.min(80, command.length())),
                    "preview", prev,
                    "output_file", WORKDIR.relativize(outputPath(taskId)).toString()));
        }

        String check(String taskId) {
            if (taskId != null) {
                var t = tasks.get(taskId);
                if (t == null) return "Error: Unknown task " + taskId;
                var visible = new LinkedHashMap<String, Object>();
                visible.put("id", t.get("id"));
                visible.put("status", t.get("status"));
                visible.put("command", t.get("command"));
                visible.put("result_preview", t.getOrDefault("result_preview", ""));
                visible.put("output_file", t.getOrDefault("output_file", ""));
                try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(visible); }
                catch (Exception e) { return visible.toString(); }
            }
            if (tasks.isEmpty()) return "No background tasks.";
            var lines = new ArrayList<String>();
            for (var entry : tasks.entrySet()) {
                var t = entry.getValue();
                String cmd = (String) t.get("command");
                String prev = (String) t.getOrDefault("result_preview", "");
                lines.add(entry.getKey() + ": [" + t.get("status") + "] "
                        + cmd.substring(0, Math.min(60, cmd.length()))
                        + " -> " + (prev.isEmpty() ? "(running)" : prev));
            }
            return String.join("\n", lines);
        }

        List<Map<String, String>> drain() {
            var raw = new ArrayList<Map<String, String>>();
            while (true) {
                var n = notifications.poll();
                if (n == null) break;
                raw.add(n);
            }
            // 按 task_id 折叠：同 task_id 只保留最后一条
            var folded = new LinkedHashMap<String, Map<String, String>>();
            for (var n : raw) { folded.put(n.get("task_id"), n); }
            return new ArrayList<>(folded.values());
        }

        List<String> detectStalled() {
            var stalled = new ArrayList<String>();
            long now = System.currentTimeMillis();
            for (var entry : tasks.entrySet()) {
                var task = entry.getValue();
                if ("running".equals(task.get("status"))) {
                    Object startedAt = task.get("started_at");
                    long start = startedAt instanceof Number n ? n.longValue() : 0L;
                    if (start > 0 && (now - start) > STALL_THRESHOLD) {
                        stalled.add(entry.getKey());
                    }
                }
            }
            return stalled;
        }
    }

    // ==================== 基础设施 ====================

    private static AnthropicClient buildClient(String apiKey, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank())
            return AnthropicOkHttpClient.builder().apiKey(apiKey).baseUrl(baseUrl).build();
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build();
    }

    private static Tool defineTool(String name, String description,
                                   Map<String, Object> properties, List<String> required) {
        var sb = Tool.InputSchema.builder().properties(JsonValue.from(properties));
        if (required != null && !required.isEmpty()) sb.putAdditionalProperty("required", JsonValue.from(required));
        return Tool.builder().name(name).description(description).inputSchema(sb.build()).build();
    }

    private static Path safePath(String p) {
        Path r = WORKDIR.resolve(p).normalize().toAbsolutePath();
        if (!r.startsWith(WORKDIR)) throw new IllegalArgumentException("Path escapes workspace: " + p);
        return r;
    }

    private static String runBash(String command) {
        if (command == null || command.isBlank()) return "Error: command is required";
        for (String d : List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"))
            if (command.contains(d)) return "Error: Dangerous command blocked";
        try {
            ProcessBuilder pb = System.getProperty("os.name").toLowerCase().contains("win")
                    ? new ProcessBuilder("cmd", "/c", command)
                    : new ProcessBuilder("bash", "-c", command);
            pb.directory(WORKDIR.toFile()).redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            if (!p.waitFor(BASH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { p.destroyForcibly(); return "Error: Timeout"; }
            if (output.isEmpty()) return "(no output)";
            return output.length() > MAX_OUTPUT ? output.substring(0, MAX_OUTPUT) : output;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runRead(String path, Integer limit) {
        try {
            var lines = Files.readAllLines(safePath(path));
            if (limit != null && limit < lines.size()) {
                lines = new ArrayList<>(lines.subList(0, limit));
                lines.add("... (" + (lines.size() - limit) + " more)");
            }
            String r = String.join("\n", lines);
            return r.length() > MAX_OUTPUT ? r.substring(0, MAX_OUTPUT) : r;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runWrite(String path, String content) {
        try { Path fp = safePath(path); Files.createDirectories(fp.getParent()); Files.writeString(fp, content); return "Wrote " + content.length() + " bytes"; }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runEdit(String path, String oldText, String newText) {
        try { Path fp = safePath(path); String c = Files.readString(fp); if (!c.contains(oldText)) return "Error: Text not found"; Files.writeString(fp, c.substring(0, c.indexOf(oldText)) + newText + c.substring(c.indexOf(oldText) + oldText.length())); return "Edited " + path; }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @SuppressWarnings("unchecked")
    private static Object jsonValueToObject(JsonValue value) {
        if (value == null) return null;
        var s = value.asString(); if (s.isPresent()) return s.get();
        var n = value.asNumber(); if (n.isPresent()) return n.get();
        var b = value.asBoolean(); if (b.isPresent()) return b.get();
        try { var m = value.asObject(); if (m.isPresent()) { Map<String, JsonValue> raw = (Map<String, JsonValue>)(Object)m.get(); var r = new LinkedHashMap<String, Object>(); for (var e : raw.entrySet()) r.put(e.getKey(), jsonValueToObject(e.getValue())); return r; } } catch (ClassCastException ignored) {}
        try { var l = value.asArray(); if (l.isPresent()) { List<JsonValue> raw = (List<JsonValue>)(Object)l.get(); var r = new ArrayList<>(); for (JsonValue i : raw) r.add(jsonValueToObject(i)); return r; } } catch (ClassCastException ignored) {}
        return null;
    }

    private static String ansiCyan(String t) { return ANSI_CYAN + t + ANSI_RESET; }
}
