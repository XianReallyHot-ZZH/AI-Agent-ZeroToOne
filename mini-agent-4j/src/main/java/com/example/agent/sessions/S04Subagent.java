package com.example.agent.sessions;

import com.anthropic.models.messages.*;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.PathSandbox;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * S04：Subagent —— 上下文隔离保护模型的思路清晰度。
 * <p>
 * 生成一个拥有全新 messages=[] 的子 Agent。
 * 子 Agent 共享文件系统但不共享对话历史，
 * 完成后仅向父 Agent 返回一段摘要。
 * <p>
 * 关键洞察："进程隔离免费提供上下文隔离。"
 * <p>
 * 对应 Python 原版：s04_subagent.py
 */
public class S04Subagent {

    /**
     * 运行子 Agent：全新上下文，工具集无 task（防递归），最多 30 轮。
     * <p>
     * 对应 Python: run_subagent(prompt)
     */
    private static String runSubagent(AgentLoop agent, String prompt, ToolDispatcher childDispatcher,
                                      List<Tool> childTools) {
        // 子 Agent 使用全新的 params builder（独立上下文）
        var subBuilder = MessageCreateParams.builder()
                .model(agent.getModel())
                .maxTokens(8000)
                .system("You are a coding subagent at " + System.getProperty("user.dir")
                        + ". Complete the given task, then summarize your findings.");

        for (Tool tool : childTools) {
            subBuilder.addTool(tool);
        }

        subBuilder.addUserMessage(prompt);

        // 子 Agent 循环，最多 30 轮
        for (int round = 0; round < 30; round++) {
            Message response = agent.getClient().messages().create(subBuilder.build());
            subBuilder.addMessage(response);

            if (!StopReason.TOOL_USE.equals(response.stopReason())) {
                // 提取最终文本摘要返回给父 Agent
                var texts = new ArrayList<String>();
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(tb -> texts.add(tb.text()));
                }
                return texts.isEmpty() ? "(no summary)" : String.join("", texts);
            }

            // 执行工具
            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>) AgentLoop.jsonValueToObject(toolUse._input());
                    String output = childDispatcher.dispatch(toolUse.name(), input != null ? input : Map.of());
                    results.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output.length() > 50000 ? output.substring(0, 50000) : output)
                                    .build()));
                }
            }
            subBuilder.addUserMessageOfBlockParams(results);
        }
        return "(subagent reached max rounds)";
    }

    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);

        String systemPrompt = "You are a coding agent at " + workDir
                + ". Use the task tool to delegate exploration or subtasks.";

        // ---- 子 Agent 工具（无 task，防递归） ----
        List<Tool> childTools = List.of(
                AgentLoop.defineTool("bash", "Run a shell command.",
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
                        List.of("path", "old_text", "new_text"))
        );

        ToolDispatcher childDispatcher = new ToolDispatcher();
        childDispatcher.register("bash", input -> BashTool.execute(input, workDir));
        childDispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        childDispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        childDispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));

        // ---- 父 Agent 工具（含 task） ----
        List<Tool> parentTools = new ArrayList<>(childTools);
        parentTools.add(AgentLoop.defineTool("task",
                "Spawn a subagent with fresh context. It shares the filesystem but not conversation history.",
                Map.of("prompt", Map.of("type", "string"),
                        "description", Map.of("type", "string", "description", "Short description of the task")),
                List.of("prompt")));

        ToolDispatcher parentDispatcher = new ToolDispatcher();
        parentDispatcher.register("bash", input -> BashTool.execute(input, workDir));
        parentDispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        parentDispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        parentDispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));

        AgentLoop agent = new AgentLoop(systemPrompt, parentTools, parentDispatcher);

        // 注册 task 工具（需要引用 agent 实例，所以在创建后注册）
        parentDispatcher.register("task", input -> {
            String desc = (String) input.getOrDefault("description", "subtask");
            String prompt = (String) input.get("prompt");
            System.out.println("> task (" + desc + "): " + prompt.substring(0, Math.min(80, prompt.length())));
            return runSubagent(agent, prompt, childDispatcher, childTools);
        });

        // ---- REPL ----
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print("\033[36ms04 >> \033[0m");
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
