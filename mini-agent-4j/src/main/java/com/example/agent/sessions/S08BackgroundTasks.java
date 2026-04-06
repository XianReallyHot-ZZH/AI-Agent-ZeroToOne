package com.example.agent.sessions;

import com.anthropic.models.messages.*;
import com.example.agent.background.BackgroundManager;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.Console;
import com.example.agent.util.PathSandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * S08：后台任务 —— 模型思考时，Harness 等待。
 * <p>
 * 长时间运行的命令在 Virtual Thread 中执行。
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
 * 关键洞察："发出即忘——Agent 不会因等待命令而阻塞。"
 * <p>
 * 对应 Python 原版：s08_background_tasks.py
 */
public class S08BackgroundTasks {

    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);
        BackgroundManager bg = new BackgroundManager(workDir);

        String systemPrompt = "You are a coding agent at " + workDir
                + ". Use background_run for long-running commands.";

        // ---- 工具定义 ----
        List<Tool> tools = List.of(
                AgentLoop.defineTool("bash", "Run a shell command (blocking).",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
                AgentLoop.defineTool("read_file", "Read file contents.",
                        Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer")),
                        List.of("path")),
                AgentLoop.defineTool("write_file", "Write content to file.",
                        Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")),
                        List.of("path", "content")),
                AgentLoop.defineTool("edit_file", "Replace exact text in file.",
                        Map.of("path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text")),
                AgentLoop.defineTool("background_run",
                        "Run command in background thread. Returns task_id immediately.",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
                AgentLoop.defineTool("check_background",
                        "Check background task status. Omit task_id to list all.",
                        Map.of("task_id", Map.of("type", "string")), null)
        );

        // ---- 工具分发器 ----
        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
        dispatcher.register("background_run", input ->
                bg.run((String) input.get("command"),
                        input.get("timeout") instanceof Number n ? n.intValue() : 120));
        dispatcher.register("check_background", input ->
                bg.check((String) input.get("task_id")));

        // ---- REPL ----
        // S08 的核心：在每次 LLM 调用前 drain 后台通知并注入对话历史
        // 这样 LLM 能看到后台任务的完成结果，并据此决定下一步操作
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);
        agent.setPreLLMHook(() -> {
            var notifs = bg.drainNotifications();
            if (notifs.isEmpty()) return null;
            var sb = new StringBuilder("<background-results>\n");
            for (var n : notifs) {
                sb.append("[bg:").append(n.get("task_id")).append("] ")
                        .append(n.get("status")).append(": ")
                        .append(n.get("result")).append("\n");
            }
            sb.append("</background-results>");
            System.out.println(Console.yellow(sb.toString().replace("<background-results>\n", "")
                    .replace("</background-results>", "").trim()));
            return List.of(ContentBlockParam.ofText(
                    TextBlockParam.builder().text(sb.toString()).build()));
        });
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {

            System.out.print(Console.cyan("s08 >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;
            paramsBuilder.addUserMessage(query);
            try {
                agent.agentLoop(paramsBuilder);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
    }
}
