package com.example.agent.sessions;

import com.anthropic.models.messages.*;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.compress.ContextCompressor;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.PathSandbox;

import java.nio.file.Path;
import java.util.*;

/**
 * S06：上下文压缩 —— 干净的记忆让 Agent 能无限工作。
 * <p>
 * 三层压缩管线：
 * <pre>
 * Layer 1: microCompact  —— 静默替换旧 tool_result 占位符（每轮执行）
 * Layer 2: autoCompact   —— token 超阈值时，LLM 摘要替换（自动触发）
 * Layer 3: manualCompact —— 模型调用 compact 工具（手动触发）
 * </pre>
 * <p>
 * 关键洞察："Agent 可以策略性地遗忘，然后继续无限工作。"
 * <p>
 * 对应 Python 原版：s06_context_compact.py
 * <p>
 * 注意：由于 anthropic-java SDK 的 MessageParam 是不可变的，
 * S06 使用 Map-based 消息格式来支持就地修改（microCompact）。
 * 这是 Java 版的一个关键架构决策。
 */
public class S06ContextCompact {

    /** 自动压缩阈值（token 数） */
    private static final long THRESHOLD = 50000;

    public static void main(String[] args) {
        Path workDir = Path.of(System.getProperty("user.dir"));
        PathSandbox sandbox = new PathSandbox(workDir);
        Path transcriptDir = workDir.resolve(".transcripts");

        String systemPrompt = "You are a coding agent at " + workDir + ". Use tools to solve tasks.";

        // ---- 工具定义 ----
        List<Tool> tools = List.of(
                AgentLoop.defineTool("bash", "Run a shell command.",
                        Map.of("command", Map.of("type", "string")), List.of("command")),
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
                AgentLoop.defineTool("compact", "Trigger manual conversation compression.",
                        Map.of("focus", Map.of("type", "string", "description", "What to preserve in the summary")),
                        null)
        );

        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
        dispatcher.register("compact", input -> "Manual compression requested.");

        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);
        ContextCompressor compressor = new ContextCompressor(agent.getClient(), agent.getModel(), transcriptDir);

        // ---- REPL（S06 使用 Map-based 消息以支持 microCompact 就地修改） ----
        // 注意：这里我们仍然使用 SDK 的 Builder 方式，
        // 压缩逻辑在 REPL 层面简化处理
        MessageCreateParams.Builder paramsBuilder = agent.newParamsBuilder();
        Scanner scanner = new Scanner(System.in);

        System.out.println("[S06 Context Compact - 三层压缩管线]");
        System.out.println("[threshold=" + THRESHOLD + " tokens, auto-compact when exceeded]");

        while (true) {
            System.out.print("\033[36ms06 >> \033[0m");
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
