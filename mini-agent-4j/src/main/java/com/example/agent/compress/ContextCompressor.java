package com.example.agent.compress;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.example.agent.util.Console;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 三层上下文压缩器：让 Agent 能无限工作。
 * <p>
 * Layer 1 - microCompact（静默，每轮执行）：
 *   替换旧 tool_result 内容为占位符，保留最新 3 条。
 * <p>
 * Layer 2 - autoCompact（token 超阈值时触发）：
 *   保存完整对话到 .transcripts/，调用 LLM 生成摘要，替换全部消息。
 * <p>
 * Layer 3 - manualCompact（模型调用 compact 工具时触发）：
 *   与 autoCompact 相同逻辑，但由模型主动触发。
 * <p>
 * 对应 Python 原版：s06_context_compact.py
 */
public class ContextCompressor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 保留最近 N 条 tool_result 不压缩 */
    private static final int KEEP_RECENT = 3;

    private final AnthropicClient client;
    private final String model;
    private final Path transcriptDir;

    public ContextCompressor(AnthropicClient client, String model, Path transcriptDir) {
        this.client = client;
        this.model = model;
        this.transcriptDir = transcriptDir;
    }

    /**
     * Layer 1: 微压缩 —— 替换旧 tool_result 内容为占位符。
     * <p>
     * 保留最近 {@value KEEP_RECENT} 条 tool_result 不压缩，
     * 更早的内容替换为 "[Previous: used {tool_name}]"。
     * <p>
     * 此操作在每次 LLM 调用前静默执行，用户无感知。
     * <p>
     * 注意：因为 SDK 的 MessageParam/ContentBlockParam 是不可变的，
     * 我们需要用 Map 结构来修改内容。这里直接操作传入的消息列表。
     * <p>
     * 对应 Python: micro_compact(messages)
     *
     * @param messages 消息列表（SDK MessageParam 无法修改，此处用于检测；
     *                 实际修改需要在调用者层面重建消息）
     */
    public void microCompact(List<Map<String, Object>> messages) {
        // 收集所有 tool_result 的引用
        var toolResults = new ArrayList<Map<String, Object>>();

        for (var msg : messages) {
            if ("user".equals(msg.get("role")) && msg.get("content") instanceof List<?> parts) {
                for (var part : parts) {
                    if (part instanceof Map<?, ?> partMap) {
                        @SuppressWarnings("unchecked")
                        var p = (Map<String, Object>) partMap;
                        if ("tool_result".equals(p.get("type"))) {
                            toolResults.add(p);
                        }
                    }
                }
            }
        }

        // 不足 KEEP_RECENT 条则不压缩
        if (toolResults.size() <= KEEP_RECENT) {
            return;
        }

        // 替换旧的 tool_result 内容为占位符
        var toClear = toolResults.subList(0, toolResults.size() - KEEP_RECENT);
        for (var result : toClear) {
            Object content = result.get("content");
            if (content instanceof String s && s.length() > 100) {
                result.put("content", "[Previous: used tool]");
            }
        }
    }

    /**
     * Layer 2: 自动压缩 —— 保存转录 + LLM 摘要 + 替换消息。
     * <p>
     * 对应 Python: auto_compact(messages)
     *
     * @param messages 当前消息列表（JSON 可序列化格式）
     * @return 压缩后的新消息列表（仅包含摘要 + 确认）
     */
    public List<Map<String, Object>> autoCompact(List<Map<String, Object>> messages) {
        // 1. 保存完整对话到 .transcripts/
        try {
            Files.createDirectories(transcriptDir);
            Path transcriptPath = transcriptDir.resolve("transcript_" + System.currentTimeMillis() + ".jsonl");

            var sb = new StringBuilder();
            for (var msg : messages) {
                sb.append(MAPPER.writeValueAsString(msg)).append("\n");
            }
            Files.writeString(transcriptPath, sb.toString());
            System.out.println(Console.dim("[transcript saved: " + transcriptPath + "]"));

            // 2. 调用 LLM 生成摘要
            String conversationText = MAPPER.writeValueAsString(messages);
            if (conversationText.length() > 80000) {
                conversationText = conversationText.substring(0, 80000);
            }

            Message response = client.messages().create(MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(2000)
                    .addUserMessage("Summarize this conversation for continuity. Include: "
                            + "1) What was accomplished, 2) Current state, 3) Key decisions made. "
                            + "Be concise but preserve critical details.\n\n" + conversationText)
                    .build());

            String summary = response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(b -> b.asText().text())
                    .reduce("", (a, b) -> a + b);

            // 3. 替换所有消息为压缩摘要
            var compressed = new ArrayList<Map<String, Object>>();
            compressed.add(Map.of("role", "user", "content",
                    "[Conversation compressed. Transcript: " + transcriptPath + "]\n\n" + summary));
            compressed.add(Map.of("role", "assistant", "content",
                    "Understood. I have the context from the summary. Continuing."));
            return compressed;

        } catch (IOException e) {
            System.out.println(Console.red("上下文压缩失败: " + e.getMessage()));
            return new ArrayList<>(messages);
        }
    }

    /**
     * 估算消息列表的 token 数。
     * <p>
     * 对应 Python: estimate_tokens(messages) = len(str(messages)) // 4
     */
    public long estimateTokens(List<Map<String, Object>> messages) {
        try {
            return MAPPER.writeValueAsString(messages).length() / 4;
        } catch (Exception e) {
            return messages.toString().length() / 4;
        }
    }
}
