package com.example.agent.tools;

import com.example.agent.util.PathSandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文件写入工具：将内容写入文件，自动创建父目录。
 * <p>
 * 所有路径操作经过 {@link PathSandbox} 校验。
 * <p>
 * 对应 Python 原版：run_write(path, content) 函数。
 */
public final class WriteTool {

    private WriteTool() {}

    /**
     * 写入文件内容。
     *
     * @param input   包含 "path" 和 "content" 键的输入参数
     * @param sandbox 路径沙箱
     * @return 操作结果
     */
    public static String execute(Map<String, Object> input, PathSandbox sandbox) {
        String pathStr = (String) input.get("path");
        String content = (String) input.get("content");

        if (pathStr == null || pathStr.isBlank()) {
            return "Error: path is required";
        }
        if (content == null) {
            return "Error: content is required";
        }

        try {
            Path safePath = sandbox.safePath(pathStr);
            // 自动创建父目录
            Files.createDirectories(safePath.getParent());
            Files.writeString(safePath, content);
            return "Wrote " + content.length() + " bytes to " + pathStr;
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
