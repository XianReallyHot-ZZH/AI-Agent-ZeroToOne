package com.example.agent.background;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 后台任务管理器：Virtual Thread + 通知队列。
 * <p>
 * 长时间运行的命令在 Virtual Thread 中执行，
 * Agent 循环不被阻塞。完成通知通过 {@link LinkedBlockingQueue} 传递，
 * 在下一次 LLM 调用前批量注入到对话中。
 * <p>
 * 并发模型：
 * <pre>
 * AgentLoop ──[run]──> BackgroundManager ──> Virtual Thread
 *    │                      │                    │
 *    │    <taskId 立即返回>   │                    │ 执行命令（阻塞）
 *    │                      │                    │
 *    │                      │   <── offer(通知) ──┘
 *    │                      │
 *    └──[drainNotifications]─> 取出所有通知
 * </pre>
 * <p>
 * 对应 Python 原版：s08_background_tasks.py 中的 BackgroundManager 类。
 * Java 21 Virtual Thread 替代 Python threading.Thread，性能更优。
 */
public class BackgroundManager {

    private static final Logger log = LoggerFactory.getLogger(BackgroundManager.class);

    /** 所有后台任务：taskId -> {status, command, result} */
    private final ConcurrentHashMap<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();

    /** 完成通知队列 */
    private final LinkedBlockingQueue<Map<String, Object>> notificationQueue = new LinkedBlockingQueue<>();

    /** 工作目录 */
    private final Path workDir;

    /** 危险命令黑名单 */
    private static final List<String> DANGEROUS = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "> /dev/"
    );

    public BackgroundManager(Path workDir) {
        this.workDir = workDir;
    }

    /**
     * 启动后台任务，立即返回 taskId。
     * <p>
     * 对应 Python: BackgroundManager.run(command)
     *
     * @param command 要执行的 shell 命令
     * @param timeout 超时秒数（默认 120）
     * @return 任务启动确认信息
     */
    public String run(String command, int timeout) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        tasks.put(taskId, new ConcurrentHashMap<>(Map.of(
                "status", "running",
                "command", command,
                "result", ""
        )));

        // 使用 Virtual Thread 执行（不阻塞平台线程）
        Thread.ofVirtual()
                .name("bg-task-" + taskId)
                .start(() -> execute(taskId, command, timeout));

        return "Background task " + taskId + " started: " + command.substring(0, Math.min(80, command.length()));
    }

    /**
     * Virtual Thread 中执行命令，完成后推送通知。
     * <p>
     * 对应 Python: BackgroundManager._execute(task_id, command)
     */
    private void execute(String taskId, String command, int timeout) {
        String output;
        String status;

        // 危险命令检查
        for (String d : DANGEROUS) {
            if (command.contains(d)) {
                output = "Error: Dangerous command blocked";
                status = "error";
                tasks.get(taskId).put("status", status);
                tasks.get(taskId).put("result", output);
                pushNotification(taskId, status, command, output);
                return;
            }
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            String rawOutput = new String(process.getInputStream().readAllBytes()).trim();
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                output = "Error: Timeout (" + timeout + "s)";
                status = "timeout";
            } else {
                output = rawOutput.isEmpty() ? "(no output)" : rawOutput;
                if (output.length() > 50000) {
                    output = output.substring(0, 50000);
                }
                status = "completed";
            }
        } catch (IOException | InterruptedException e) {
            output = "Error: " + e.getMessage();
            status = "error";
        }

        tasks.get(taskId).put("status", status);
        tasks.get(taskId).put("result", output);
        pushNotification(taskId, status, command, output);
    }

    /**
     * 推送完成通知到队列。
     */
    private void pushNotification(String taskId, String status, String command, String result) {
        notificationQueue.offer(Map.of(
                "task_id", taskId,
                "status", status,
                "command", command.substring(0, Math.min(80, command.length())),
                "result", result.substring(0, Math.min(500, result.length()))
        ));
        log.info("[bg-task-{}] {} 完成: {}", taskId, status, result.substring(0, Math.min(100, result.length())));
    }

    /**
     * 检查任务状态。
     * <p>
     * 对应 Python: BackgroundManager.check(task_id)
     *
     * @param taskId 任务 ID（null 时列出全部）
     */
    public String check(String taskId) {
        if (taskId != null && !taskId.isEmpty()) {
            var t = tasks.get(taskId);
            if (t == null) return "Error: Unknown task " + taskId;
            return "[" + t.get("status") + "] " + t.get("command") + "\n" + t.getOrDefault("result", "(running)");
        }

        if (tasks.isEmpty()) return "No background tasks.";

        var lines = new ArrayList<String>();
        for (var entry : tasks.entrySet()) {
            var t = entry.getValue();
            String cmd = t.get("command").toString();
            lines.add(entry.getKey() + ": [" + t.get("status") + "] "
                    + cmd.substring(0, Math.min(60, cmd.length())));
        }
        return String.join("\n", lines);
    }

    /**
     * 批量获取所有完成通知（drain 语义）。
     * <p>
     * 对应 Python: BackgroundManager.drain_notifications()
     *
     * @return 通知列表（可能为空）
     */
    public List<Map<String, Object>> drainNotifications() {
        var notifs = new ArrayList<Map<String, Object>>();
        notificationQueue.drainTo(notifs);
        return notifs;
    }
}
