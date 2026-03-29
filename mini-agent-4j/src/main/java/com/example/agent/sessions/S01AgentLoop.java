package com.example.agent.sessions;

import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.tools.BashTool;
import com.example.agent.util.Console;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * S01：Agent 循环 —— 模型与真实世界的第一次连接。
 * <p>
 * AI 编码 Agent 的全部秘密浓缩在一个模式中：
 * <pre>
 *   while (stopReason == TOOL_USE) {
 *       response = LLM(messages, tools);
 *       execute tools;
 *       append results;
 *   }
 * </pre>
 * <p>
 * 这是最小可运行的 Agent：一个循环 + 一个 bash 工具。
 * 产品级 Agent 在此基础上层叠策略、钩子和生命周期控制。
 * <p>
 * 对应 Python 原版：s01_agent_loop.py
 *
 * @see <a href="../../../../../../vendors/learn-claude-code/agents/s01_agent_loop.py">Python 原版</a>
 */
public class S01AgentLoop {

    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));

        // ---- 系统提示词 ----
        String systemPrompt = "You are a coding agent at " + workDir
                + ". Use bash to solve tasks. Act, don't explain.";

        // ---- 工具定义：仅 bash ----
        List<Tool> tools = List.of(
                AgentLoop.defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")),
                        List.of("command"))
        );

        // ---- 工具分发器 ----
        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));

        // ---- 创建 Agent 循环 ----
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);

        // ---- REPL 主循环 ----
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print(Console.cyan("s01 >> "));
            if (!scanner.hasNextLine()) break;

            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) {
                break;
            }

            // 追加用户消息
            paramsBuilder.addUserMessage(query);

            // 执行 Agent 循环
            try {
                agent.agentLoop(paramsBuilder);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
    }
}
