package com.example.agent.tasks;

import com.example.agent.util.Console;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * <p>
 * <hr>
 * <h3>已知局限性 &amp; 待改进项</h3>
 *
 * <h4>1. 性能：全量扫描，历史任务越多越慢</h4>
 * <ul>
 *   <li>{@link #listAll()} 和 {@link #clearDependency(int)} 都会扫描 .tasks/ 下所有 task_*.json，
 *       逐个反序列化。时间复杂度 O(n)，n 为全部任务数量（含已完成）。</li>
 *   <li>没有按状态分区或维护索引文件（如 index.json），已完成的任务与活跃任务混在一起，
 *       每次操作都要为历史数据付出磁盘 IO 和 JSON 解析的代价。</li>
 *   <li>{@code listAll()} 会把所有任务（含已完成）都返回给 LLM，大量 {@code [x]} 行
 *       浪费上下文窗口 token。应支持过滤或归档已完成任务。</li>
 * </ul>
 *
 * <h4>2. 并发安全：零保护，多线程/多实例不可用</h4>
 * <ul>
 *   <li>{@code nextId} 是普通 {@code int}，不是 {@code AtomicInteger}，也没有 {@code synchronized}。
 *       两个线程同时调用 {@link #create(String, String)} 可能生成相同 ID，互相覆盖文件。</li>
 *   <li>文件读写没有 {@link java.nio.channels.FileLock}。{@link #load(int)} + {@link #save(Map)}
 *       之间存在 read-modify-write 竞态窗口：两个线程读同一任务 → 各自修改 → 后写覆盖先写。</li>
 *   <li>{@link #update(int, String, List, List)} 中双向依赖写入（addBlocks 分支）和
 *       {@link #clearDependency(int)} 的全量扫描在并发下都可能丢失更新。</li>
 *   <li>当前 REPL 单线程使用没问题；多 agent 并行（如 S11/S12 方向）需补锁。</li>
 * </ul>
 *
 * <h4>3. 数据一致性：删除不清理依赖、无环检测</h4>
 * <ul>
 *   <li>{@code update(taskId, "deleted", ...)} 直接删除文件，<b>没有调用</b>
 *       {@link #clearDependency(int)}。其他任务的 {@code blockedBy}/{@code blocks} 数组中
 *       会留下指向已删除任务的悬空 ID，造成"永远被阻塞"的死锁假象。</li>
 *   <li>{@code addBlockedBy}/{@code addBlocks} 只是往 Set 里加 ID，没有检测是否形成环。
 *       如果 A blockedBy B 且 B blockedBy A，两个任务都永远无法开始，且无机制发现此问题。
 *       应在添加依赖时做 DAG 环检测。</li>
 * </ul>
 *
 * <h4>4. 上下文膨胀：listAll 无过滤</h4>
 * <ul>
 *   <li>{@code listAll()} 把所有历史任务都返回给 LLM，随着任务积压，输出会越来越长，
 *       挤占宝贵的上下文窗口。应支持 {@code status} 过滤参数，或自动归档已完成任务。</li>
 * </ul>
 *
 * <p>
 * <b>总结</b>：当前实现是 MVP / 教学演示级别，核心设计方向（文件持久化、DAG 依赖）
 * 是正确的。生产化需补：(1) 索引或按状态分目录，避免全量扫描；(2) 文件锁或改为数据库，
 * 解决并发安全；(3) 删除时清理依赖 + DAG 环检测，保证数据一致性；(4) 归档/清理策略，
 * 控制上下文膨胀。
 */
public class TaskManager {

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
            System.out.println(Console.red("创建 .tasks 目录失败: " + e.getMessage()));
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
