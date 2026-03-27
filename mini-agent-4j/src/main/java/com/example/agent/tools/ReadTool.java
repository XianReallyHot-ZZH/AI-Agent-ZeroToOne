package com.example.agent.tools;

import com.example.agent.util.PathSandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 文件读取工具：读取文件内容，支持行数限制。
 * <p>
 * 所有路径操作经过 {@link PathSandbox} 校验，防止路径穿越。
 * <p>
 * 对应 Python 原版：run_read(path, limit) 函数。
 */
public final class ReadTool {

    /** 输出最大长度 */
    private static final int MAX_OUTPUT = 50000;

    private ReadTool() {}

    /**
     * 读取文件内容。
     *
     * @param input   包含 "path"（必需）和 "limit"（可选）键的输入参数
     * @param sandbox 路径沙箱
     * @return 文件内容字符串
     */
    public static String execute(Map<String, Object> input, PathSandbox sandbox) {
        String pathStr = (String) input.get("path");
        if (pathStr == null || pathStr.isBlank()) {
            return "Error: path is required";
        }

        // 获取可选的行数限制
        Integer limit = null;
        Object limitObj = input.get("limit");
        if (limitObj instanceof Number num) {
            limit = num.intValue();
        }

        try {
            Path safePath = sandbox.safePath(pathStr);
            List<String> lines = Files.readAllLines(safePath);

            // 应用行数限制
            if (limit != null && limit > 0 && limit < lines.size()) {
                lines = new java.util.ArrayList<>(lines.subList(0, limit));
                lines.add("... (" + (Files.readAllLines(safePath).size() - limit) + " more lines)");
            }

            String result = String.join("\n", lines);
            // 截断过长输出
            return result.length() > MAX_OUTPUT
                    ? result.substring(0, MAX_OUTPUT)
                    : result;

        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
