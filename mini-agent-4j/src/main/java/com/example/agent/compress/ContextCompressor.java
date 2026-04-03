package com.example.agent.compress;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.example.agent.util.Console;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 *
 * <h3>生产级 microCompact 设计思考</h3>
 *
 * 当前实现是教学级简化：按固定数量（KEEP_RECENT=3）一刀切压缩，
 * 不区分 tool_result 的信息密度和可恢复性。生产环境中需要更精细的策略：
 *
 * <h4>1. 按工具类型分级压缩</h4>
 * 不同工具返回值的信息密度和可恢复性差异巨大，应采用不同压缩策略：
 * <ul>
 *   <li>{@code write_file / edit_file} — 信息密度低（仅确认消息），可恢复性高（已写入磁盘）
 *       → 立即压缩为结构化摘要 {@code "[Previous: wrote {path}]"}</li>
 *   <li>{@code read_file} — 信息密度高（源码内容），可恢复性高（可重新读取）
 *       → 保留摘要（路径 + 行数），丢弃完整内容</li>
 *   <li>{@code bash} — 信息密度低（大量输出），可恢复性低（命令可能有副作用/时效性）
 *       → 截断输出保留首尾，而非完全替换</li>
 *   <li>{@code search / grep} — 信息密度中（搜索结果），可恢复性高（可重跑）
 *       → 保留文件路径列表，丢弃行内容</li>
 *   <li>{@code http_request / API 调用} — 信息密度高（API 响应），可恢复性低（临时 token、时效数据）
 *       → 保守压缩：保留完整内容，最后才考虑压缩</li>
 * </ul>
 *
 * <h4>2. 保留语义摘要而非无意义占位符</h4>
 * 当前 {@code "[Previous: used read_file]"} 丢失太多上下文。
 * 生产级应保留结构化摘要：
 * <pre>
 *   // 现在（教学级）：
 *   "[Previous: used read_file]"
 *
 *   // 生产级：
 *   {"_compressed": true, "tool": "read_file", "path": "src/main.py",
 *    "lines": 142, "summary": "Python module with FastAPI routes"}
 * </pre>
 * 摘要来源：(a) 工具返回时附带 summary 字段（成本几乎为零）；
 *          (b) 对关键结果调用 LLM 生成一句话摘要。
 *
 * <h4>3. 基于语义依赖而非简单计数</h4>
 * 时间近 ≠ 语义相关。应追踪工具调用间的依赖链：
 * <pre>
 *   read_file("config.json") → result_A
 *     └─ bash("deploy --config config.json") → result_B（依赖 A）
 *          └─ read_file("deploy.log") → result_C
 * </pre>
 * 只要 result_B 还在活跃窗口内，result_A 就不应被压缩。
 * 可通过在 tool_result 中添加 {@code depends_on} 字段实现。
 *
 * <h4>4. 分层 token 预算而非固定阈值</h4>
 * 按 token 预算分配，而非固定 KEEP_RECENT 计数：
 * <pre>
 *   int budgetTokens = 10000;  // tool_result 总预算
 *   int usedTokens = 0;
 *   // 从最新的 tool_result 开始保留，直到用完预算
 *   for (i = results.size - 1; i >= 0; i--) {
 *       int cost = estimateTokens(results[i]);
 *       if (usedTokens + cost > budgetTokens) compress(results[i]);
 *       else usedTokens += cost;
 *   }
 * </pre>
 * 短结果（{@code "Wrote 42 bytes"}）几乎不占预算，可以保留很多条；
 * 长结果（5000 行文件内容）会更快触发压缩。
 *
 * <h4>5. 生产级 microCompact 最终形态</h4>
 * <pre>
 *   microCompact(messages, config):
 *       toolResults = 收集所有 tool_result
 *
 *       // 1. 按工具类型决定压缩策略
 *       for result in toolResults:
 *           strategy = config.getStrategy(result.toolName)
 *           // write/edit → 立即压缩为结构化摘要
 *           // read → 保留摘要（路径 + 行数 + summary）
 *           // bash → 截断长输出，保留首尾
 *           // http → 保守处理
 *
 *       // 2. 在剩余 token 预算内从新到旧保留
 *       remainingBudget = config.toolResultTokenBudget
 *       for result in toolResults.reversed():
 *           cost = estimateTokens(result)
 *           if cost > remainingBudget: applyCompression(result, strategy)
 *           else remainingBudget -= cost
 *
 *       // 3. 压缩时保留结构化摘要而非无意义占位符
 * </pre>
 * 核心思路：从"按数量一刀切"变成"按信息价值 + 可恢复性 + token 预算做精细化压缩"。
 * 信息密度高且不可恢复的数据多保留；信息密度低或可随时重新获取的数据激进压缩。
 */
public class ContextCompressor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 保留最近 N 条 tool_result 不压缩。
     * <p>
     * Trade-off 说明：
     * microCompact 在每次 LLM 调用前执行，当 tool_result 总数超过此阈值时，
     * 较早的 tool_result 内容会被替换为 "[Previous: used {tool_name}]" 占位符。
     * <p>
     * 风险：如果单轮 agent_loop 中模型连续调用 > KEEP_RECENT 次工具，且后续步骤
     * 依赖较早的 tool_result 内容（例如对比第 1 次和第 5 次调用的输出），则信息会丢失。
     * <p>
     * 设计假设：
     * 1. 模型通常在下一轮就消费了 tool_result（读完文件→立即编辑），信息已被回复吸收
     * 2. 如果需要旧数据，模型可以重新调用工具（占位符告知模型之前调用过什么）
     * 3. 不压缩则上下文无限增长→最终超过 token 限制→agent 崩溃
     * <p>
     * 这是有意的取舍："Agent 可以策略性地遗忘，然后继续无限工作。"
     */
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
        // 1. 收集所有 tool_result 的引用
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

        // 2. 构建 tool_use_id → tool_name 映射（扫描所有 assistant 消息）
        var toolNameMap = new LinkedHashMap<String, String>();
        for (var msg : messages) {
            if ("assistant".equals(msg.get("role")) && msg.get("content") instanceof List<?> parts) {
                for (var part : parts) {
                    if (part instanceof Map<?, ?> partMap) {
                        @SuppressWarnings("unchecked")
                        var p = (Map<String, Object>) partMap;
                        if ("tool_use".equals(p.get("type"))) {
                            String id = (String) p.get("id");
                            String name = (String) p.get("name");
                            if (id != null && name != null) {
                                toolNameMap.put(id, name);
                            }
                        }
                    }
                }
            }
        }

        // 3. 替换旧的 tool_result 内容为占位符（保留最近 KEEP_RECENT 条）
        var toClear = toolResults.subList(0, toolResults.size() - KEEP_RECENT);
        for (var result : toClear) {
            Object content = result.get("content");
            if (content instanceof String s && s.length() > 100) {
                String toolId = (String) result.get("tool_use_id");
                String toolName = toolNameMap.getOrDefault(toolId != null ? toolId : "", "unknown");
                result.put("content", "[Previous: used " + toolName + "]");
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
