package com.example.agent.sessions;

import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.tasks.TodoManager;
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
 * S03：TodoWrite —— 让模型自己跟踪进度。
 * <p>
 * 模型通过 TodoManager 维护结构化任务列表。
 * 如果连续 3 轮未更新 todo，Agent 循环注入 {@code <reminder>} 提醒。
 * <p>
 * 关键洞察："Agent 可以自己跟踪进度——而我能看到。"
 * <p>
 * 对应 Python 原版：s03_todo_write.py
 */
public class S03TodoWrite {

    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);
        TodoManager todo = new TodoManager();

        // ---- 系统提示词 ----
        String systemPrompt = "You are a coding agent at " + workDir + ".\n"
                + "Use the todo tool to plan multi-step tasks. "
                + "Mark in_progress before starting, completed when done.\n"
                + "Prefer tools over prose.";

        // ---- 工具定义 ----
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
                        List.of("path", "old_text", "new_text")),
                AgentLoop.defineTool("todo", "Update task list. Track progress on multi-step tasks.",
                        Map.of("items", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "id", Map.of("type", "string"),
                                                "text", Map.of("type", "string"),
                                                "status", Map.of("type", "string",
                                                        "enum", List.of("pending", "in_progress", "completed"))),
                                        "required", List.of("id", "text", "status")))),
                        List.of("items"))
        );

        // ---- 工具分发器 ----
        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
        dispatcher.register("todo", input -> {
            @SuppressWarnings("unchecked")
            List<?> items = (List<?>) input.get("items");
            return todo.update(items);
        });

        // ---- 创建带 nag reminder 的 Agent 循环 ----
        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);

        // Nag reminder 状态：连续未调用 todo 的轮数
        final int[] roundsSinceTodo = {0};

        agent.setRoundHook(toolNames -> {
            boolean usedTodo = toolNames.contains("todo");
            roundsSinceTodo[0] = usedTodo ? 0 : roundsSinceTodo[0] + 1;
            if (roundsSinceTodo[0] >= 3) {
                return List.of(ContentBlockParam.ofText(
                        TextBlockParam.builder()
                                .text("<reminder>Update your todos. You haven't updated the task list recently.</reminder>")
                                .build()));
            }
            return null;
        });

        // ---- REPL ----
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        while (true) {
            System.out.print(Console.cyan("s03 >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            paramsBuilder.addUserMessage(query);
            roundsSinceTodo[0] = 0;  // 每次用户提问重置 nag 计数器
            try {
                agent.agentLoop(paramsBuilder);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
    }
}
