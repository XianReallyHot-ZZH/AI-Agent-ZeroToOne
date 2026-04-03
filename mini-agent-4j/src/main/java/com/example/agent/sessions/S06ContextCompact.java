package com.example.agent.sessions;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.example.agent.core.AgentLoop;
import com.example.agent.core.ToolDispatcher;
import com.example.agent.compress.ContextCompressor;
import com.example.agent.tools.BashTool;
import com.example.agent.tools.EditTool;
import com.example.agent.tools.ReadTool;
import com.example.agent.tools.WriteTool;
import com.example.agent.util.Console;
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
 * S06 使用 Map-based 消息格式来支持就地修改（microCompact），
 * 并实现自己的 agent loop 来在每次 LLM 调用前插入压缩逻辑。
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
                        Map.of("focus", Map.of("type", "string",
                                "description", "What to preserve in the summary")),
                        null)
        );

        // ---- 工具注册（compact 不注册，由 s06AgentLoop 自行拦截处理） ----
        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register("bash", input -> BashTool.execute(input, workDir));
        dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
        dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
        dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));

        AgentLoop agent = new AgentLoop(systemPrompt, tools, dispatcher);
        AnthropicClient client = agent.getClient();
        String model = agent.getModel();

        ContextCompressor compressor = new ContextCompressor(client, model, transcriptDir);

        // ---- REPL（S06 使用 Map-based 消息以支持 microCompact 就地修改） ----
        List<Map<String, Object>> messages = new ArrayList<>();
        Scanner scanner = new Scanner(System.in);

        System.out.println("[S06 Context Compact - 三层压缩管线]");
        System.out.println("[threshold=" + THRESHOLD + " tokens, auto-compact when exceeded]");

        while (true) {
            System.out.print(Console.cyan("s06 >> "));
            if (!scanner.hasNextLine()) break;
            String query = scanner.nextLine().trim();
            if (query.isEmpty() || "q".equalsIgnoreCase(query) || "exit".equalsIgnoreCase(query)) break;

            // 追加用户消息
            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", query);
            messages.add(userMsg);

            try {
                s06AgentLoop(messages, client, model, systemPrompt, tools, dispatcher, compressor);
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
            System.out.println();
        }
    }

    // ==================== 自定义 Agent Loop（集成三层压缩） ====================

    /**
     * S06 自定义 agent loop —— 在每次 LLM 调用前插入压缩逻辑。
     * <p>
     * 对应 Python: agent_loop(messages) 函数
     */
    private static void s06AgentLoop(
            List<Map<String, Object>> messages,
            AnthropicClient client, String model, String systemPrompt,
            List<Tool> tools, ToolDispatcher dispatcher,
            ContextCompressor compressor) {

        while (true) {
            // ---- Layer 1: microCompact（每轮静默压缩，只针对tool_result结果，只保留最近的三条tool_result） ----
            // 效果不好，先注释掉
//            compressor.microCompact(messages);

            // ---- Layer 2: autoCompact（token 超阈值时触发，效果是将历史对话都进行了压缩，只保留了一轮压缩后的对话） ----
            if (compressor.estimateTokens(messages) > THRESHOLD) {
                System.out.println(Console.dim("[auto_compact triggered]"));
                var compressed = compressor.autoCompact(messages);
                messages.clear();
                messages.addAll(compressed);
            }

            // ---- 调用 LLM ----
            MessageCreateParams params = buildSdkParams(messages, model, systemPrompt, tools);
            Message response = client.messages().create(params);

            // ---- 追加 assistant 回复到消息列表 ----
            appendAssistantMessage(messages, response);

            // ---- 检查是否继续执行工具 ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                // 模型决定停止，打印文本回复
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(tb -> System.out.println(tb.text()));
                }
                return;
            }

            // ---- 遍历 content blocks，执行工具调用 ----
            List<Map<String, Object>> toolResultBlocks = new ArrayList<>();
            boolean manualCompact = false;

            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    String toolName = toolUse.name();
                    String output;

                    if ("compact".equals(toolName)) {
                        // Layer 3: compact 工具由 loop 自行拦截
                        manualCompact = true;
                        output = "Compressing...";
                        System.out.println("> " + Console.bold("compact") + ": " + Console.dim("Compressing..."));
                    } else {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> input = (Map<String, Object>) AgentLoop.jsonValueToObject(toolUse._input());
                        output = dispatcher.dispatch(toolName, input != null ? input : Map.of());
                    }

                    // 构造 tool_result Map
                    Map<String, Object> resultBlock = new LinkedHashMap<>();
                    resultBlock.put("type", "tool_result");
                    resultBlock.put("tool_use_id", toolUse.id());
                    resultBlock.put("content", output);
                    toolResultBlocks.add(resultBlock);
                }
            }

            // ---- 追加 tool_result 为 user 消息 ----
            Map<String, Object> toolResultMsg = new LinkedHashMap<>();
            toolResultMsg.put("role", "user");
            toolResultMsg.put("content", toolResultBlocks);
            messages.add(toolResultMsg);

            // ---- Layer 3: 手动压缩触发 ----
            if (manualCompact) {
                System.out.println(Console.dim("[manual compact]"));
                var compressed = compressor.autoCompact(messages);
                messages.clear();
                messages.addAll(compressed);
            }
        }
    }

    // ==================== Map ↔ SDK 消息格式转换 ====================

    /**
     * 将 Map-based 消息列表转换为 SDK MessageCreateParams。
     * <p>
     * 每次 LLM 调用前重建（因为 Map 列表可能被压缩修改）。
     */
    private static MessageCreateParams buildSdkParams(
            List<Map<String, Object>> messages,
            String model, String systemPrompt, List<Tool> tools) {

        var builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(8000)
                .system(systemPrompt);

        for (Tool tool : tools) {
            builder.addTool(tool);
        }

        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            MessageParam.Role sdkRole = "user".equals(role)
                    ? MessageParam.Role.USER
                    : MessageParam.Role.ASSISTANT;
            Object content = msg.get("content");

            if (content instanceof String s) {
                // 纯文本消息
                builder.addMessage(MessageParam.builder()
                        .role(sdkRole)
                        .content(MessageParam.Content.ofString(s))
                        .build());
            } else if (content instanceof List<?> parts) {
                // 结构化消息（tool_use / tool_result / text blocks）
                List<ContentBlockParam> blocks = new ArrayList<>();
                for (Object part : parts) {
                    if (part instanceof Map<?, ?> partMap) {
                        @SuppressWarnings("unchecked")
                        var p = (Map<String, Object>) partMap;
                        String type = (String) p.get("type");
                        switch (type) {
                            case "text" -> blocks.add(ContentBlockParam.ofText(
                                    TextBlockParam.builder()
                                            .text((String) p.get("text"))
                                            .build()));
                            case "tool_use" -> {
                                Object inputObj = p.get("input");
                                blocks.add(ContentBlockParam.ofToolUse(
                                        ToolUseBlockParam.builder()
                                                .id((String) p.get("id"))
                                                .name((String) p.get("name"))
                                                .input(inputObj != null
                                                        ? JsonValue.from(inputObj)
                                                        : JsonValue.from(Map.of()))
                                                .build()));
                            }
                            case "tool_result" -> blocks.add(ContentBlockParam.ofToolResult(
                                    ToolResultBlockParam.builder()
                                            .toolUseId((String) p.get("tool_use_id"))
                                            .content(String.valueOf(p.get("content")))
                                            .build()));
                        }
                    }
                }
                builder.addMessage(MessageParam.builder()
                        .role(sdkRole)
                        .content(MessageParam.Content.ofBlockParams(blocks))
                        .build());
            }
        }

        return builder.build();
    }

    /**
     * 将 SDK Message 响应转换为 Map 格式并追加到消息列表。
     */
    private static void appendAssistantMessage(List<Map<String, Object>> messages, Message response) {
        List<Map<String, Object>> contentBlocks = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                Map<String, Object> textBlock = new LinkedHashMap<>();
                textBlock.put("type", "text");
                textBlock.put("text", block.asText().text());
                contentBlocks.add(textBlock);
            } else if (block.isToolUse()) {
                ToolUseBlock toolUse = block.asToolUse();
                Map<String, Object> toolUseBlock = new LinkedHashMap<>();
                toolUseBlock.put("type", "tool_use");
                toolUseBlock.put("id", toolUse.id());
                toolUseBlock.put("name", toolUse.name());
                toolUseBlock.put("input", AgentLoop.jsonValueToObject(toolUse._input()));
                contentBlocks.add(toolUseBlock);
            }
        }
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", contentBlocks);
        messages.add(assistantMsg);
    }
}
