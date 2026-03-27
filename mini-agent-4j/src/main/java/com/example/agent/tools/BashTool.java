package com.example.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bash 工具：通过 ProcessBuilder 执行 shell 命令。
 * <p>
 * 安全特性：
 * - 危险命令黑名单检查
 * - 120 秒超时
 * - 输出截断 50000 字符
 * - OS 自适应（Unix 用 bash -c，Windows 用 cmd /c）
 * <p>
 * 对应 Python 原版：run_bash(command) 函数。
 */
public final class BashTool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    /** 危险命令黑名单 */
    private static final List<String> DANGEROUS = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    /** 默认超时（秒） */
    private static final int DEFAULT_TIMEOUT = 120;

    /** 输出最大长度 */
    private static final int MAX_OUTPUT = 50000;

    private BashTool() {}

    /**
     * 执行 shell 命令。
     *
     * @param input 包含 "command" 键的输入参数
     * @param workDir 工作目录
     * @return 命令输出（stdout + stderr）
     */
    public static String execute(Map<String, Object> input, Path workDir) {
        String command = (String) input.get("command");
        if (command == null || command.isBlank()) {
            return "Error: command is required";
        }
        return run(command, workDir, DEFAULT_TIMEOUT);
    }

    /**
     * 执行 shell 命令（核心实现）。
     */
    public static String run(String command, Path workDir, int timeoutSeconds) {
        // 危险命令检查
        for (String dangerous : DANGEROUS) {
            if (command.contains(dangerous)) {
                return "Error: Dangerous command blocked";
            }
        }

        try {
            // OS 自适应：选择 shell
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", command);
            } else {
                pb = new ProcessBuilder("bash", "-c", command);
            }

            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true); // 合并 stdout 和 stderr

            Process process = pb.start();

            // 读取输出
            StringBuilder output = new StringBuilder();
            try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append("\n");
                    }
                    output.append(line);
                    // 提前截断，避免内存溢出
                    if (output.length() > MAX_OUTPUT) {
                        break;
                    }
                }
            }

            // 等待进程结束
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (" + timeoutSeconds + "s)";
            }

            String result = output.toString().trim();
            if (result.isEmpty()) {
                return "(no output)";
            }
            // 截断过长输出
            return result.length() > MAX_OUTPUT
                    ? result.substring(0, MAX_OUTPUT)
                    : result;

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
