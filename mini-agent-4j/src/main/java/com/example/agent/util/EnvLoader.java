package com.example.agent.util;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvBuilder;

/**
 * 环境变量加载器。
 * <p>
 * 封装 dotenv-java，统一管理 .env 文件和系统环境变量的读取。
 * 优先读取 .env 文件，若不存在则回退到系统环境变量。
 * <p>
 * 对应 Python 原版：每个 agent 文件顶部的 load_dotenv(override=True) 逻辑。
 */
public final class EnvLoader {

    private static final Dotenv dotenv;

    static {
        // 尝试加载 .env 文件；文件不存在时不报错，回退到系统环境变量
        dotenv = new DotenvBuilder()
                .ignoreIfMissing()
                .systemProperties()
                .load();
    }

    private EnvLoader() {
        // 工具类，禁止实例化
    }

    /**
     * 获取 Anthropic API 密钥。
     *
     * @return API 密钥字符串
     * @throws IllegalStateException 如果未配置 ANTHROPIC_API_KEY
     */
    public static String getApiKey() {
        String key = get("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "ANTHROPIC_API_KEY 未配置。请在 .env 文件或系统环境变量中设置。");
        }
        return key;
    }

    /**
     * 获取模型 ID（如 claude-sonnet-4-20250514）。
     *
     * @return 模型 ID 字符串
     * @throws IllegalStateException 如果未配置 MODEL_ID
     */
    public static String getModelId() {
        String model = get("MODEL_ID");
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "MODEL_ID 未配置。请在 .env 文件或系统环境变量中设置。");
        }
        return model;
    }

    /**
     * 获取自定义 API 基础 URL（可选）。
     * <p>
     * 用于兼容第三方 API 端点。未配置时返回 null。
     *
     * @return 基础 URL 字符串，或 null
     */
    public static String getBaseUrl() {
        return get("ANTHROPIC_BASE_URL");
    }

    /**
     * 通用环境变量读取：优先 .env 文件，回退到系统环境变量。
     */
    public static String get(String key) {
        // dotenv 已经整合了 .env 和系统环境变量
        return dotenv.get(key);
    }
}
