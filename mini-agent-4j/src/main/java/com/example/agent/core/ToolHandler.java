package com.example.agent.core;

import java.util.Map;

/**
 * 工具处理器函数式接口。
 * <p>
 * 每个工具（bash、read_file 等）都实现此接口。
 * 使用 {@code @FunctionalInterface} 支持 Lambda 表达式注册。
 * <p>
 * 对应 Python 原版：TOOL_HANDLERS 字典中的 lambda 函数。
 */
@FunctionalInterface
public interface ToolHandler {

    /**
     * 执行工具操作。
     *
     * @param input 工具输入参数（来自 LLM 的 tool_use 请求）
     * @return 工具执行结果字符串
     */
    String handle(Map<String, Object> input);
}
