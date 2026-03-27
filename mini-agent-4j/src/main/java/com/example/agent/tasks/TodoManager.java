package com.example.agent.tasks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Todo 管理器：模型通过结构化列表跟踪自身进度。
 * <p>
 * 核心规则：
 * - 最多 20 项
 * - 最多 1 个 in_progress 状态
 * - 每项必须有 id、text、status
 * <p>
 * 如果模型连续 3 轮未更新 todo，Agent 循环会注入 {@code <reminder>} 提醒。
 * <p>
 * 对应 Python 原版：s03_todo_write.py 中的 TodoManager 类。
 */
public class TodoManager {

    /** 当前 todo 列表 */
    private List<Map<String, String>> items = new ArrayList<>();

    /**
     * 更新整个 todo 列表（全量替换）。
     * <p>
     * 对应 Python: TodoManager.update(items)
     *
     * @param items 新的 todo 列表（每项需包含 id、text、status）
     * @return 渲染后的 todo 状态字符串
     * @throws IllegalArgumentException 校验失败时抛出
     */
    @SuppressWarnings("unchecked")
    public String update(List<?> rawItems) {
        if (rawItems.size() > 20) {
            throw new IllegalArgumentException("Max 20 todos allowed");
        }

        List<Map<String, String>> validated = new ArrayList<>();
        int inProgressCount = 0;

        for (int i = 0; i < rawItems.size(); i++) {
            Map<String, Object> item = (Map<String, Object>) rawItems.get(i);
            String id = String.valueOf(item.getOrDefault("id", String.valueOf(i + 1)));
            String text = String.valueOf(item.getOrDefault("text",
                    item.getOrDefault("content", ""))).trim();
            String status = String.valueOf(item.getOrDefault("status", "pending")).toLowerCase();

            if (text.isEmpty()) {
                throw new IllegalArgumentException("Item " + id + ": text required");
            }
            if (!List.of("pending", "in_progress", "completed").contains(status)) {
                throw new IllegalArgumentException("Item " + id + ": invalid status '" + status + "'");
            }
            if ("in_progress".equals(status)) {
                inProgressCount++;
            }

            validated.add(Map.of("id", id, "text", text, "status", status));
        }

        if (inProgressCount > 1) {
            throw new IllegalArgumentException("Only one task can be in_progress at a time");
        }

        this.items = validated;
        return render();
    }

    /**
     * 渲染 todo 列表为可读字符串。
     * <p>
     * 格式：[ ] / [>] / [x] #id: text
     * <p>
     * 对应 Python: TodoManager.render()
     */
    public String render() {
        if (items.isEmpty()) {
            return "No todos.";
        }

        var lines = new ArrayList<String>();
        for (var item : items) {
            String marker = switch (item.get("status")) {
                case "pending" -> "[ ]";
                case "in_progress" -> "[>]";
                case "completed" -> "[x]";
                default -> "[?]";
            };
            lines.add(marker + " #" + item.get("id") + ": " + item.get("text"));
        }

        long done = items.stream()
                .filter(item -> "completed".equals(item.get("status")))
                .count();
        lines.add("\n(" + done + "/" + items.size() + " completed)");

        return String.join("\n", lines);
    }

    /**
     * 检查是否有未完成的 todo 项。
     * <p>
     * 用于 nag reminder 判断：如果有未完成项且 3 轮未更新，注入提醒。
     */
    public boolean hasOpenItems() {
        return items.stream().anyMatch(item -> !"completed".equals(item.get("status")));
    }
}
