package com.example.agent.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具分发器：根据工具名称路由到对应的 {@link ToolHandler}。
 * <p>
 * 维护一个 {@code Map<String, ToolHandler>}，
 * 收到 LLM 的 tool_use 请求时，查找并执行对应的处理器。
 * <p>
 * 对应 Python 原版：TOOL_HANDLERS 字典 + 分发逻辑。
 *
 * <pre>
 * // 使用示例
 * var dispatcher = new ToolDispatcher();
 * dispatcher.register("bash", input -> runBash((String) input.get("command")));
 * String result = dispatcher.dispatch("bash", Map.of("command", "ls"));
 * </pre>
 */
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    /** 工具名 -> 处理器映射（保持注册顺序） */
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    /**
     * 注册工具处理器。
     *
     * @param name    工具名称（与 LLM 的 tool 定义中的 name 一致）
     * @param handler 处理器实现
     */
    public void register(String name, ToolHandler handler) {
        handlers.put(name, handler);
    }

    /**
     * 分发工具调用。
     * <p>
     * 异常统一捕获，转为 "Error: ..." 字符串返回（不中断 Agent 循环）。
     *
     * @param toolName 工具名称
     * @param input    工具输入参数
     * @return 工具执行结果
     */
    public String dispatch(String toolName, Map<String, Object> input) {
        ToolHandler handler = handlers.get(toolName);
        if (handler == null) {
            return "Error: Unknown tool: " + toolName;
        }
        try {
            String result = handler.handle(input);
            // 日志输出前 200 字符（与 Python 版对齐）
            String preview = result.length() > 200
                    ? result.substring(0, 200) + "..."
                    : result;
            log.info("> {}: {}", toolName, preview);
            return result;
        } catch (Exception e) {
            log.warn("> {} 执行异常: {}", toolName, e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 检查是否注册了指定工具。
     */
    public boolean hasHandler(String name) {
        return handlers.containsKey(name);
    }
}
