# s14：定时调度

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12 > s13 > [ s14 ] s15 > s16 > s17 > s18 > s19`

> *"调度记住未来的工作，然后在时间到达时将其交还给同一个主循环。"* —— Agent 不仅能响应现在，还能规划未来。

## 课程目标

理解如何为 Agent 构建基于 cron 表达式的定时调度系统。Agent 可以调度未来的任务，当时间到达时自动将提示词注入主对话循环。

## 问题

有些工作需要在特定时间执行：每天早上检查构建状态、每周一生成报告、每隔 5 分钟监控服务健康。如果 Agent 不能调度未来工作，用户就必须手动在正确的时间发出指令。

## 方案

后台线程每秒检查一次调度表，匹配的任务将通知推入队列，主循环在下次 LLM 调用前排空并注入：

```
+-------------------------------+
|  后台线程                      |
|  (每秒检查一次)                |
|                               |
|  对每个任务:                    |
|    if cronMatches(now):       |
|      将通知加入队列             |
+-------------------------------+
          |
          v
[notification_queue]
          |
     (agentLoop 顶部排空)
          |
          v
[在 LLM 调用前作为 user 消息注入]
```

Cron 表达式支持 5 个字段：

```
+-------+-------+-------+-------+-------+
| min   | hour  | dom   | month | dow   |
| 0-59  | 0-23  | 1-31  | 1-12  | 0-6   |
+-------+-------+-------+-------+-------+
*/5 * * * *     → 每 5 分钟
0 9 * * 1       → 每周一上午 9:00
30 14 * * *     → 每天 14:30
```

## 核心概念

### CronScheduler —— 调度器

```java
public static class CronScheduler {
    private final List<Map<String, Object>> tasks;                        // 任务列表
    private final ConcurrentLinkedQueue<String> notificationQueue;        // 通知队列
    private final AtomicInteger lastCheckMinute;                          // 每分钟最多触发一次

    public void start();          // 加载持久化任务，启动后台线程
    public String create(...);    // 创建新任务
    public String delete(...);    // 删除任务
    public String listTasks();    // 列出所有任务
    public List<String> drainNotifications();  // 排空通知队列
}
```

### cronMatches —— 5 字段匹配

```java
public static boolean cronMatches(String expr, LocalDateTime dt) {
    String[] fields = expr.trim().split("\\s+");
    // 逐字段检查：分钟、小时、日、月、星期
    // 支持: *(任意) */N(步进) N(精确) N-M(范围) N,M(列表)
}
```

### 两种持久化模式

| 模式         | 存储                       | 生命周期           |
|-------------|---------------------------|-------------------|
| session-only | 内存列表                   | 退出后丢失         |
| durable      | .claude/scheduled_tasks.json | 退出后保留      |

### 两种触发模式

| 模式       | 行为                           |
|-----------|-------------------------------|
| recurring | 重复执行，7 天后自动过期         |
| one-shot  | 触发一次后自动删除              |

### 抖动机制

recuring 任务如果指向精确的 :00 或 :30 分钟，添加确定性抖动偏移（1-4 分钟），避免所有任务在整点集中触发：

```java
private int computeJitter(String cronExpr) {
    int minuteVal = Integer.parseInt(fields[0]);
    if (JITTER_MINUTES.contains(minuteVal)) {
        return (Math.abs(cronExpr.hashCode()) % JITTER_OFFSET_MAX) + 1;
    }
    return 0;
}
```

## 关键代码片段

Agent 循环在每次 LLM 调用前排空调度通知：

```java
while (true) {
    // 排空调度通知并注入对话
    List<String> notifications = scheduler.drainNotifications();
    for (String note : notifications) {
        paramsBuilder.addUserMessage(note);
    }

    // 正常 LLM 调用
    Message response = client.messages().create(paramsBuilder.build());
    // ...
}
```

3 个调度工具定义：

```java
defineTool("cron_create", "Schedule a recurring or one-shot task with a cron expression.",
    Map.of("cron", ..., "prompt", ..., "recurring", ..., "durable", ...),
    List.of("cron", "prompt"));

defineTool("cron_delete", "Delete a scheduled task by ID.",
    Map.of("id", ...), List.of("id"));

defineTool("cron_list", "List all scheduled tasks.", Map.of(), null);
```

REPL 测试命令：

```
/cron      # 列出所有调度任务
/test      # 手动注入测试通知
```

## 变更对比

| 组件          | S13           | S14                                  |
|---------------|---------------|--------------------------------------|
| Cron 匹配     | （无）        | 5 字段 cron 表达式解析               |
| 调度器        | （无）        | CronScheduler（后台线程 + 通知队列） |
| 持久化        | （无）        | .claude/scheduled_tasks.json         |
| 调度工具      | （无）        | cron_create / cron_delete / cron_list |
| Agent 循环    | 标准循环      | 循环顶部排空通知队列                 |
| 抖动          | （无）        | 整点任务确定性偏移                   |
| 自动过期      | （无）        | 7 天后自动清理                       |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S14CronScheduler"
```

1. 输入 `创建一个每分钟执行的定时任务，检查当前时间并报告`
2. 等待 1 分钟，观察通知被自动注入
3. 输入 `/cron` 查看任务列表
4. 输入 `/test` 手动触发一条测试通知
5. 创建一个 durable 任务，退出后重启，观察任务自动加载

## 要点总结

1. 后台线程每秒检查，每分钟每任务最多触发一次
2. 通知队列在 Agent 循环顶部排空，注入为 user 消息
3. Cron 表达式支持标准语法：*、*/N、N-M、N,M
4. durable 任务持久化到磁盘，session-only 只在内存
5. recurring 任务 7 天自动过期，one-shot 触发后自动删除
6. 抖动机制防止整点集中触发
