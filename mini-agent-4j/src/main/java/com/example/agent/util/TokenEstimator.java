package com.example.agent.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 粗略 Token 估算器。
 * <p>
 * 使用「字符数 / 4」的经验公式估算 token 数量。
 * 这是一个保守估算，实际 token 数可能略少，
 * 但足够用于决定是否触发上下文压缩。
 * <p>
 * 对应 Python 原版：estimate_tokens(messages) 函数。
 */
public final class TokenEstimator {

    /** 每个 token 大约对应的字符数 */
    private static final int CHARS_PER_TOKEN = 4;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TokenEstimator() {
        // 工具类，禁止实例化
    }

    /**
     * 估算文本的 token 数。
     *
     * @param text 输入文本
     * @return 估算 token 数
     */
    public static long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / CHARS_PER_TOKEN;
    }

    /**
     * 估算消息列表的 token 数。
     * <p>
     * 将消息列表序列化为 JSON 字符串后估算。
     *
     * @param messages 消息列表
     * @return 估算 token 数
     */
    public static long estimate(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        try {
            String json = MAPPER.writeValueAsString(messages);
            return json.length() / CHARS_PER_TOKEN;
        } catch (Exception e) {
            // 序列化失败时使用 toString() 兜底
            return messages.toString().length() / CHARS_PER_TOKEN;
        }
    }
}
