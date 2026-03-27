package com.example.agent.sessions;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.PathSandbox;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * S02：工具分发 —— 扩展模型能触达的世界。
 * <p>
 * Agent 循环没有任何变化。我们只是往工具数组里加了工具，
 * 然后用一个分发表（dispatch map）来路由调用。
 * <p>
 * 关键洞察："循环根本没改。我只是加了工具。"
 * <p>
 * 对应 Python 原版：s02_tool_use.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s02_tool_use.py">Python 原版</a>
 */
public class S02ToolUse {

    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);

        // ---- 系统提示词 ----
        String systemPrompt = "You are a coding agent at " + workDir
                + ". Use tools to solve tasks. Act, don't explain.";

        // ---- 工具定义：bash + read_file + write_file + edit_file ----
        List<Tool> tools = List.of(
                AgentLoop.defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")),
                        List.of("command")),

                AgentLoop.defineTool("read_file", "Read file contents.",
                        Map.of("path", Map.of("type", "string"),
                                "limit", Map.of("type", "integer")),
                        List.of("path")),

                AgentLoop.defineTool("write_file", "Write content to file.",
                        Map.of("path", Map.of("type", "string"),
                                "content", Map.of("type", "string")),
                        List.of("path", "content")),

                AgentLoop.defineTool("edit_file", "Replace exact text in file.",
                        Map.of("path", Map.of("type", "string"),
                                "old_text", Map.of("type", "string"),
                                "new_text", Map.of("type", "string")),
                        List.of("path", "old_text", "new_text"))
        );

        // ---- 工具分发器（dispatch map） ----
        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));

        // ---- 创建 Agent 循环 ----
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);

        // ---- REPL 主循环 ----
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\033[36ms02 >> \033[0m");
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

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
