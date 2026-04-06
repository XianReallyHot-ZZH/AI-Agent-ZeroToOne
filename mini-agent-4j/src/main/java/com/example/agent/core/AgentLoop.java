package com.example.agent.core;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.example.agent.util.EnvLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 核心循环：LLM 调用 → 工具执行 → 结果回传。
 * <p>
 * 这是整个 Agent 系统的心脏。核心模式极其简单：
 * <pre>
 *   while (stopReason == TOOL_USE) {
 *       response = LLM(messages, tools);
 *       execute tools;
 *       append results;
 *   }
 * </pre>
 * <p>
 * 所有后续课程（s02-s12）都是在这个循环上增加"装置"（Harness），
 * 循环本身从不改变。
 * <p>
 * 对应 Python 原版：agent_loop(messages) 函数。
 */
public class AgentLoop {

    /** Anthropic API 客户端（线程安全，可复用） */
    private final AnthropicClient client;

    /** 模型 ID */
    private final String model;

    /** 系统提示词 */
    private final String systemPrompt;

    /** 工具定义列表（发送给 LLM） */
    private final List<Tool> tools;

    /** 工具分发器（执行工具调用） */
    private final ToolDispatcher dispatcher;

    /** 最大 token 数 */
    private final long maxTokens;

    /** 每轮工具执行后的回调（可选），null 表示不拦截 */
    private RoundHook roundHook;

    /** 每次 LLM 调用前的回调（可选），null 表示不拦截 */
    private PreLLMHook preLLMHook;

    /**
     * 每轮工具执行后的回调接口（可选）。
     * <p>
     * 用于注入额外内容（如 nag reminder）到工具结果中。
     * 返回的 blocks 会被插入到工具结果列表的头部。
     */
    @FunctionalInterface
    public interface RoundHook {
        /**
         * 每轮工具执行完毕后调用。
         *
         * @param toolNames 本轮调用的工具名称列表
         * @return 要注入的额外内容块，或 null/空列表表示不注入
         */
        List<ContentBlockParam> afterRound(List<String> toolNames);
    }

    /**
     * 每次 LLM 调用前的回调接口（可选）。
     * <p>
     * 用于在调用 LLM 前注入额外消息（如后台任务完成通知）。
     * 返回的 user/assistant 消息对会被追加到对话历史中。
     * <p>
     * 与 {@link RoundHook} 对称：
     * <ul>
     *   <li>{@link RoundHook} — 工具执行后（S03 nag reminder）</li>
     *   <li>{@link PreLLMHook} — LLM 调用前（S08 后台通知注入）</li>
     * </ul>
     */
    @FunctionalInterface
    public interface PreLLMHook {
        /**
         * 每次 LLM 调用前触发。
         *
         * @return 要注入的消息对列表（user + assistant），或 null/空列表表示不注入
         */
        List<ContentBlockParam> beforeLLMCall();
    }

    /**
     * 设置每轮工具执行后的回调。
     */
    public void setRoundHook(RoundHook roundHook) {
        this.roundHook = roundHook;
    }

    /**
     * 设置每次 LLM 调用前的回调。
     */
    public void setPreLLMHook(PreLLMHook preLLMHook) {
        this.preLLMHook = preLLMHook;
    }

    /**
     * 创建 Agent 循环实例。
     *
     * @param systemPrompt 系统提示词
     * @param tools        工具定义列表
     * @param dispatcher   工具分发器
     */
    public AgentLoop(String systemPrompt, List<Tool> tools, ToolDispatcher dispatcher) {
        this(systemPrompt, tools, dispatcher, 16000);
    }

    /**
     * 创建 Agent 循环实例（指定 maxTokens）。
     */
    public AgentLoop(String systemPrompt, List<Tool> tools, ToolDispatcher dispatcher, long maxTokens) {
        this.client = buildClient();
        this.model = EnvLoader.getModelId();
        this.systemPrompt = systemPrompt;
        this.tools = tools;
        this.dispatcher = dispatcher;
        this.maxTokens = maxTokens;
    }

    /**
     * 构建 Anthropic 客户端。
     * <p>
     * 支持自定义 baseUrl（用于第三方 API 兼容端点）。
     */
    private static AnthropicClient buildClient() {
        String baseUrl = EnvLoader.getBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            return AnthropicOkHttpClient.builder()
                    .apiKey(EnvLoader.getApiKey())
                    .baseUrl(baseUrl)
                    .build();
        }
        // 使用默认端点，从环境变量读取 API Key
        return AnthropicOkHttpClient.builder()
                .apiKey(EnvLoader.getApiKey())
                .build();
    }

    /**
     * 执行 Agent 循环。
     * <p>
     * 持续调用 LLM 并执行工具，直到 LLM 决定停止（stopReason != TOOL_USE）。
     * 消息历史直接在传入的 builder 上累积。
     *
     * @param paramsBuilder 消息创建参数构建器（包含已有对话历史）
     */
    public void agentLoop(MessageCreateParams.Builder paramsBuilder) {
        while (true) {
            // ---- 0. 调用 PreLLMHook（如后台通知注入） ----
            if (preLLMHook != null) {
                List<ContentBlockParam> injected = preLLMHook.beforeLLMCall();
                if (injected != null && !injected.isEmpty()) {
                    paramsBuilder.addUserMessageOfBlockParams(injected);
                    // 模型需要 "assistant 确认" 来保持对话连贯（与 Python 版一致）
                    paramsBuilder.addAssistantMessage("Noted background results.");
                }
            }

            // ---- 1. 调用 LLM ----
            Message response = client.messages().create(paramsBuilder.build());

            // ---- 2. 将 assistant 回复追加到历史 ----
            paramsBuilder.addMessage(response);

            // ---- 3. 检查是否需要继续执行工具 ----
            if (!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                // 模型决定停止，打印文本回复
                for (ContentBlock block : response.content()) {
                    block.text().ifPresent(textBlock ->
                            System.out.println(textBlock.text()));
                }
                return;
            }

            // ---- 4. 遍历 content blocks，执行工具调用 ----
            List<ContentBlockParam> toolResults = new ArrayList<>();
            List<String> toolNames = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    String toolName = toolUse.name();
                    toolNames.add(toolName);

                    // 从 JsonValue 提取输入参数
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());

                    // 执行工具
                    String output = dispatcher.dispatch(toolName, input != null ? input : Map.of());

                    // 构造 tool_result
                    toolResults.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(toolUse.id())
                                    .content(output)
                                    .build()));
                }
            }

            // ---- 4.5 调用 RoundHook（如 nag reminder） ----
            if (roundHook != null) {
                List<ContentBlockParam> extra = roundHook.afterRound(toolNames);
                if (extra != null && !extra.isEmpty()) {
                    List<ContentBlockParam> combined = new ArrayList<>(extra);
                    combined.addAll(toolResults);
                    toolResults = combined;
                }
            }

            // ---- 5. 将工具结果追加为 user 消息 ----
            paramsBuilder.addUserMessageOfBlockParams(toolResults);
        }
    }

    /**
     * 创建预配置的 MessageCreateParams.Builder。
     * <p>
     * 已设置 model、maxTokens、system prompt 和 tools。
     * 调用者只需追加 user message 即可开始对话。
     */
    public MessageCreateParams.Builder newParamsBuilder() {
        var builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(systemPrompt);

        // 注册所有工具
        for (Tool tool : tools) {
            builder.addTool(tool);
        }

        return builder;
    }

    /**
     * 将 JsonValue 转换为普通 Java 对象（Map/List/String/Number/Boolean）。
     */
    @SuppressWarnings("unchecked")
    public static Object jsonValueToObject(JsonValue value) {
        if (value == null) return null;

        // 尝试作为 String（优先检查，因为最常见）
        var strOpt = value.asString();
        if (strOpt.isPresent()) {
            return strOpt.get();
        }

        // 尝试作为 Number
        var numOpt = value.asNumber();
        if (numOpt.isPresent()) {
            return numOpt.get();
        }

        // 尝试作为 Boolean
        var boolOpt = value.asBoolean();
        if (boolOpt.isPresent()) {
            return boolOpt.get();
        }

        // 尝试作为 Map（Object）
        // asObject() 返回 Optional<Map<String, JsonValue>>，Java 类型擦除需要 unchecked cast
        try {
            var mapOpt = value.asObject();
            if (mapOpt.isPresent()) {
                @SuppressWarnings("unchecked")
                var map = (Map<String, JsonValue>) (Object) mapOpt.get();
                var result = new java.util.LinkedHashMap<String, Object>();
                for (var entry : map.entrySet()) {
                    result.put(entry.getKey(), jsonValueToObject(entry.getValue()));
                }
                return result;
            }
        } catch (ClassCastException ignored) {}

        // 尝试作为 List（Array）
        try {
            var listOpt = value.asArray();
            if (listOpt.isPresent()) {
                @SuppressWarnings("unchecked")
                var list = (List<JsonValue>) (Object) listOpt.get();
                var result = new ArrayList<Object>();
                for (var item : list) {
                    result.add(jsonValueToObject(item));
                }
                return result;
            }
        } catch (ClassCastException ignored) {}

        return null;
    }

    /**
     * 获取 Anthropic 客户端（供子类或高级场景使用）。
     */
    public AnthropicClient getClient() {
        return client;
    }

    /**
     * 获取模型 ID。
     */
    public String getModel() {
        return model;
    }

    // ==================== 工具定义辅助方法 ====================

    /**
     * 便捷方法：构建一个 Tool 定义。
     * <p>
     * 示例：
     * <pre>
     * Tool bashTool = AgentLoop.defineTool("bash", "Run a shell command.",
     *     Map.of("command", Map.of("type", "string")),
     *     List.of("command"));
     * </pre>
     */
    public static Tool defineTool(String name, String description,
                                  Map<String, Object> properties,
                                  List<String> required) {
        var schemaBuilder = Tool.InputSchema.builder()
                .properties(JsonValue.from(properties));

        if (required != null && !required.isEmpty()) {
            schemaBuilder.putAdditionalProperty("required", JsonValue.from(required));
        }

        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schemaBuilder.build())
                .build();
    }
}
