package com.example.agent.util;

import java.util.Map;

/**
 * 控制台 ANSI 颜色工具：统一日志输出风格。
 * <p>
 * Claude Code 风格：工具调用粗体+灰色参数，结果默认色，错误红色。
 */
public final class Console {

    private Console() {}

    // ---- ANSI 转义码 ----
    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String DIM    = "\033[2m";
    private static final String RED    = "\033[31m";
    private static final String GREEN  = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN   = "\033[36m";

    /** 检测当前终端是否支持 ANSI 颜色（Windows Terminal / ConEmu / 大多数 Unix 终端） */
    private static final boolean ANSI_SUPPORTED = isAnsiSupported();

    private static boolean isAnsiSupported() {
        String term = System.getenv("TERM");
        if (term != null && !term.isEmpty()) return true;
        // Windows Terminal, ConEmu, VS Code 终端等
        String wt = System.getenv("WT_SESSION");
        if (wt != null) return true;
        String conEmu = System.getenv("ConEmuANSI");
        if ("ON".equalsIgnoreCase(conEmu)) return true;
        // 大多数现代 Windows 10+ 终端也支持
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String ansi(String code, String text) {
        if (!ANSI_SUPPORTED) return text;
        return code + text + RESET;
    }

    // ---- 基础颜色方法 ----

    public static String bold(String text)   { return ansi(BOLD, text); }
    public static String dim(String text)    { return ansi(DIM, text); }
    public static String red(String text)    { return ansi(RED, text); }
    public static String green(String text)  { return ansi(GREEN, text); }
    public static String yellow(String text) { return ansi(YELLOW, text); }
    public static String cyan(String text)   { return ansi(CYAN, text); }

    // ---- 工具日志格式化 ----

    /**
     * 格式化工具调用行：> bold(toolName): dim(参数摘要)
     * <p>
     * 参数摘要策略：
     * - bash → command
     * - read_file → path
     * - write_file → path + content 长度
     * - edit_file → path
     * - 其他 → 第一个字符串参数
     */
    public static String toolCall(String toolName, Map<String, Object> input) {
        String summary = toolParamSummary(toolName, input);
        return bold("> " + toolName) + ": " + dim(summary);
    }

    /**
     * 格式化工具结果行（灰色缩进，截断到 maxLen 字符）。
     */
    public static String toolResult(String result, int maxLen) {
        String preview = result.length() > maxLen
                ? result.substring(0, maxLen) + "..."
                : result;
        // 多行结果每行缩进两个空格
        String indented = preview.lines()
                .map(line -> "  " + line)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("  (empty)");
        return dim(indented);
    }

    /**
     * 格式化工具错误行（红色）。
     */
    public static String toolError(String toolName, String message) {
        return red("> " + toolName + " ERROR: " + message);
    }

    // ---- 工具参数摘要 ----

    private static String toolParamSummary(String toolName, Map<String, Object> input) {
        if (input == null || input.isEmpty()) return "";

        return switch (toolName) {
            case "bash" -> str(input, "command");
            case "read_file" -> str(input, "path") + limitSuffix(input);
            case "write_file" -> str(input, "path") + contentLenSuffix(input);
            case "edit_file" -> str(input, "path");
            default -> input.values().stream()
                    .filter(v -> v instanceof String)
                    .map(Object::toString)
                    .findFirst()
                    .orElse(input.toString());
        };
    }

    private static String str(Map<String, Object> input, String key) {
        Object v = input.get(key);
        return v != null ? v.toString() : "";
    }

    private static String limitSuffix(Map<String, Object> input) {
        Object limit = input.get("limit");
        return limit != null ? " (limit=" + limit + ")" : "";
    }

    private static String contentLenSuffix(Map<String, Object> input) {
        Object content = input.get("content");
        if (content instanceof String s) {
            return " (" + s.length() + " chars)";
        }
        return "";
    }
}
