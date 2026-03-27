package com.example.agent.tools;

import com.example.agent.util.PathSandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 文件编辑工具：精确文本替换（仅替换第一次出现）。
 * <p>
 * 所有路径操作经过 {@link PathSandbox} 校验。
 * <p>
 * 对应 Python 原版：run_edit(path, old_text, new_text) 函数。
 */
public final class EditTool {

    private EditTool() {}

    /**
     * 替换文件中的文本。
     *
     * @param input   包含 "path"、"old_text"、"new_text" 键的输入参数
     * @param sandbox 路径沙箱
     * @return 操作结果
     */
    public static String execute(Map<String, Object> input, PathSandbox sandbox) {
        String pathStr = (String) input.get("path");
        String oldText = (String) input.get("old_text");
        String newText = (String) input.get("new_text");

        if (pathStr == null || pathStr.isBlank()) {
            return "Error: path is required";
        }
        if (oldText == null) {
            return "Error: old_text is required";
        }
        if (newText == null) {
            return "Error: new_text is required";
        }

        try {
            Path safePath = sandbox.safePath(pathStr);
            String content = Files.readString(safePath);

            if (!content.contains(oldText)) {
                return "Error: Text not found in " + pathStr;
            }

            // 仅替换第一次出现（与 Python 版 replace(old, new, 1) 对齐）
            String updated = content.replaceFirst(
                    java.util.regex.Pattern.quote(oldText),
                    java.util.regex.Matcher.quoteReplacement(newText));
            Files.writeString(safePath, updated);
            return "Edited " + pathStr;

        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
