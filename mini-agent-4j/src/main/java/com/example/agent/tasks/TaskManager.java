package com.example.agent.tasks;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 任务管理器：文件持久化 + DAG 依赖图。
 * <p>
 * 任务以 JSON 文件存储在 .tasks/ 目录：
 * <pre>
 * .tasks/
 *   task_1.json  {"id":1, "subject":"...", "status":"completed", ...}
 *   task_2.json  {"id":2, "blockedBy":[1], "status":"pending", ...}
 * </pre>
 * <p>
 * 依赖解析：完成任务时自动从其他任务的 blockedBy 列表中移除。
 * <p>
 * 关键洞察："状态存在对话之外——因为它在文件系统上。"
 * <p>
 * 对应 Python 原版：s07_task_system.py 中的 TaskManager 类。
 */
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 任务文件目录 */
    private final Path dir;

    /** 下一个可用 ID */
    private int nextId;

    public TaskManager(Path tasksDir) {
        this.dir = tasksDir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("创建 .tasks 目录失败: {}", e.getMessage());
        }
        this.nextId = maxId() + 1;
    }

    /**
     * 扫描现有任务文件，获取最大 ID。
     */
    private int maxId() {
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .mapToInt(p -> {
                        String name = p.getFileName().toString();
                        return Integer.parseInt(name.substring(5, name.length() - 5));
                    })
                    .max()
                    .orElse(0);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * 加载单个任务。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> load(int taskId) {
        Path path = dir.resolve("task_" + taskId + ".json");
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Task " + taskId + " not found");
        }
        try {
            return MAPPER.readValue(Files.readString(path), Map.class);
        } catch (IOException e) {
            throw new RuntimeException("读取任务文件失败: " + e.getMessage());
        }
    }

    /**
     * 保存任务到文件。
     */
    private void save(Map<String, Object> task) {
        try {
            int id = ((Number) task.get("id")).intValue();
            Path path = dir.resolve("task_" + id + ".json");
            Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task));
        } catch (IOException e) {
            throw new RuntimeException("保存任务文件失败: " + e.getMessage());
        }
    }

    /**
     * 创建新任务。
     * <p>
     * 对应 Python: TaskManager.create(subject, description)
     */
    public String create(String subject, String description) {
        var task = new LinkedHashMap<String, Object>();
        task.put("id", nextId);
        task.put("subject", subject);
        task.put("description", description != null ? description : "");
        task.put("status", "pending");
        task.put("owner", "");
        task.put("blockedBy", new ArrayList<Integer>());
        task.put("blocks", new ArrayList<Integer>());
        save(task);
        nextId++;
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (IOException e) {
            return task.toString();
        }
    }

    /**
     * 获取任务详情。
     */
    public String get(int taskId) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(load(taskId));
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 更新任务状态或依赖。
     * <p>
     * 对应 Python: TaskManager.update(task_id, status, add_blocked_by, add_blocks)
     */
    @SuppressWarnings("unchecked")
    public String update(int taskId, String status,
                         List<Integer> addBlockedBy, List<Integer> addBlocks) {
        var task = load(taskId);

        if (status != null) {
            if (!List.of("pending", "in_progress", "completed", "deleted").contains(status)) {
                throw new IllegalArgumentException("Invalid status: " + status);
            }
            task.put("status", status);

            // 完成任务时，从其他任务的 blockedBy 中移除
            if ("completed".equals(status)) {
                clearDependency(taskId);
            }

            // 删除任务
            if ("deleted".equals(status)) {
                try {
                    Files.deleteIfExists(dir.resolve("task_" + taskId + ".json"));
                    return "Task " + taskId + " deleted";
                } catch (IOException e) {
                    return "Error: " + e.getMessage();
                }
            }
        }

        if (addBlockedBy != null && !addBlockedBy.isEmpty()) {
            List<Integer> current = (List<Integer>) task.getOrDefault("blockedBy", new ArrayList<>());
            var merged = new LinkedHashSet<>(current);
            merged.addAll(addBlockedBy);
            task.put("blockedBy", new ArrayList<>(merged));
        }

        if (addBlocks != null && !addBlocks.isEmpty()) {
            List<Integer> current = (List<Integer>) task.getOrDefault("blocks", new ArrayList<>());
            var merged = new LinkedHashSet<>(current);
            merged.addAll(addBlocks);
            task.put("blocks", new ArrayList<>(merged));

            // 双向更新：也更新被阻塞任务的 blockedBy
            for (int blockedId : addBlocks) {
                try {
                    var blocked = load(blockedId);
                    List<Integer> blockedBy = (List<Integer>) blocked.getOrDefault("blockedBy", new ArrayList<>());
                    if (!blockedBy.contains(taskId)) {
                        blockedBy.add(taskId);
                        blocked.put("blockedBy", blockedBy);
                        save(blocked);
                    }
                } catch (Exception ignored) {}
            }
        }

        save(task);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(task);
        } catch (IOException e) {
            return task.toString();
        }
    }

    /**
     * 完成任务时，从所有其他任务的 blockedBy 列表中移除。
     * <p>
     * 对应 Python: _clear_dependency(completed_id)
     */
    @SuppressWarnings("unchecked")
    private void clearDependency(int completedId) {
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .forEach(path -> {
                        try {
                            var task = MAPPER.readValue(Files.readString(path), Map.class);
                            List<Integer> blockedBy = (List<Integer>) task.getOrDefault("blockedBy", List.of());
                            if (blockedBy.contains(completedId)) {
                                blockedBy = new ArrayList<>(blockedBy);
                                blockedBy.remove(Integer.valueOf(completedId));
                                task.put("blockedBy", blockedBy);
                                save(task);
                            }
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    /**
     * 列出所有任务。
     * <p>
     * 对应 Python: TaskManager.list_all()
     */
    @SuppressWarnings("unchecked")
    public String listAll() {
        try (var stream = Files.list(dir)) {
            var tasks = stream
                    .filter(p -> p.getFileName().toString().matches("task_\\d+\\.json"))
                    .sorted()
                    .map(p -> {
                        try {
                            return MAPPER.readValue(Files.readString(p), Map.class);
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();

            if (tasks.isEmpty()) {
                return "No tasks.";
            }

            var lines = new ArrayList<String>();
            for (var t : tasks) {
                String status = (String) t.getOrDefault("status", "?");
                String marker = switch (status) {
                    case "pending" -> "[ ]";
                    case "in_progress" -> "[>]";
                    case "completed" -> "[x]";
                    default -> "[?]";
                };
                String owner = t.get("owner") != null && !t.get("owner").toString().isEmpty()
                        ? " @" + t.get("owner") : "";
                List<Integer> blockedBy = (List<Integer>) t.getOrDefault("blockedBy", List.of());
                String blocked = !blockedBy.isEmpty() ? " (blocked by: " + blockedBy + ")" : "";
                lines.add(marker + " #" + t.get("id") + ": " + t.get("subject") + owner + blocked);
            }
            return String.join("\n", lines);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 认领任务。
     * <p>
     * 对应 Python: TaskManager.claim(tid, owner)
     */
    public String claim(int taskId, String owner) {
        var task = load(taskId);
        task.put("owner", owner);
        task.put("status", "in_progress");
        save(task);
        return "Claimed task #" + taskId + " for " + owner;
    }
}
