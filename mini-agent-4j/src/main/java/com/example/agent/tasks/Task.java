package com.example.agent.tasks;

import java.util.List;

/**
 * 任务记录：不可变数据载体。
 * <p>
 * 对应 Python 中 .tasks/task_N.json 的 JSON 结构。
 * 使用 Java 21 record 实现，自动生成 equals/hashCode/toString。
 *
 * @param id          任务 ID（自增）
 * @param subject     任务标题
 * @param description 任务描述
 * @param status      状态：pending / in_progress / completed
 * @param owner       所有者（Agent 名称，空串表示未认领）
 * @param blockedBy   阻塞此任务的前置任务 ID 列表
 * @param blocks      被此任务阻塞的后置任务 ID 列表
 */
public record Task(
        int id,
        String subject,
        String description,
        String status,
        String owner,
        List<Integer> blockedBy,
        List<Integer> blocks
) {}
