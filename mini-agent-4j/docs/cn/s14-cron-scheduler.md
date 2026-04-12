# s14：定时调度

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12 > s13 > [ s14 ] > s15 > s16 > s17 > s18 > s19`

> *如果后台任务解决的是"稍后回来拿结果"，那么定时调度解决的是"将来某个时间再开始做事"。*

## 这一章要解决什么问题

`s13` 已经让系统学会了把慢命令放到后台。

但后台任务默认还是"现在就启动"。

很多真实需求并不是现在做，而是：

- 每天晚上跑一次测试
- 每周一早上生成报告
- 30 分钟后提醒我继续检查某个结果

如果没有调度能力，用户就只能每次手动再说一遍。
这会让系统看起来像"只能响应当下"，而不是"能安排未来工作"。

所以这一章要加上的能力是：

**把一条未来要执行的意图，先记下来，等时间到了再触发。**

## 建议联读

- 如果你还没完全分清 `schedule`、`task`、`runtime task` 各自表示什么，先回 [s13a-runtime-task-model.md](./s13a-runtime-task-model.md)。
- 如果你想重新看清"一条触发最终是怎样回到主循环里的"，可以配合读 [s00b-one-request-lifecycle.md](./s00b-one-request-lifecycle.md)。
- 如果你开始把"未来触发"误以为"又多了一套执行系统"，先回 [data-structures.md](./data-structures.md)，确认调度记录和运行时记录不是同一个表。

## 先解释几个名词

### 什么是调度器

调度器，就是一段专门负责"看时间、查任务、决定是否触发"的代码。

在 Java 实现里，调度器是 `CronScheduler` 内部类，它在后台线程中每秒检查一次所有任务。

### 什么是 cron 表达式

`cron` 是一种很常见的定时写法。

最小 5 字段版本长这样：

```text
分 时 日 月 周
```

例如：

```text
*/5 * * * *   每 5 分钟
0 9 * * 1     每周一 9 点
30 14 * * *   每天 14:30
```

如果你是初学者，不用先背全。

这一章真正重要的不是语法细节，而是：

> "系统如何把一条未来任务记住，并在合适时刻放回主循环。"

### 什么是持久化调度

持久化，意思是：

> 就算程序重启，这条调度记录还在。

Java 实现通过 `durable` 标记区分两种存储模式：

| 模式           | 存储                           | 生命周期       |
|---------------|-------------------------------|---------------|
| session-only  | 内存列表                       | 退出后丢失     |
| durable       | `.claude/scheduled_tasks.json` | 退出后保留     |

## 最小心智模型

先把调度看成 3 个部分：

```text
1. 调度记录 (ScheduleRecord)
2. 定时检查器 (checkLoop 后台线程)
3. 通知队列 (ConcurrentLinkedQueue<String>)
```

它们之间的关系是：

```text
cron_create(cron_expr, prompt, recurring, durable)
  ->
把记录写到任务列表（durable 时还落盘到 .claude/scheduled_tasks.json）
  ->
后台线程每秒看一次"当前分钟是否匹配"
  ->
如果匹配，就把 prompt 放进通知队列
  ->
主循环下一轮把它当成新的用户消息喂给模型
```

这条链路很重要。

因为它说明了一点：

**定时调度并不是另一套 agent。它最终还是回到同一条主循环。**

## 关键数据结构

### 1. ScheduleRecord

```java
Map<String, Object> task = new LinkedHashMap<>();
task.put("id",         "job_001");           // 唯一编号（UUID 前 8 位）
task.put("cron",       "0 9 * * 1");         // 定时规则
task.put("prompt",     "Run the weekly status report.");  // 到点后注入主循环的提示
task.put("recurring",  true);                // 是否反复触发
task.put("durable",    true);                // 是否落盘保存
task.put("createdAt",  System.currentTimeMillis() / 1000.0);  // 创建时间（epoch 秒）
// recurring 时额外添加：
task.put("jitter_offset", computeJitter("0 9 * * 1"));  // 抖动偏移（Java 特有）
```

字段含义：

- `id`：唯一编号
- `cron`：定时规则（5 字段 cron 表达式）
- `prompt`：到点后要注入主循环的提示
- `recurring`：是不是反复触发（false 为一次性，触发后自动删除）
- `durable`：是否落盘保存
- `createdAt`：创建时间
- `jitter_offset`：抖动偏移（Java 特有，见下文）

### 2. 调度通知

```java
// 匹配时推入通知队列的字符串
String notification = "[Scheduled task job_001]: Run the weekly status report.";
// 使用 ConcurrentLinkedQueue<String>，线程安全
```

### 3. 检查周期

Java 实现的后台线程每秒检查一次，但通过 `AtomicInteger lastCheckMinute` 保证每分钟最多触发一次：

```java
int currentMinute = now.getHour() * 60 + now.getMinute();
if (currentMinute != lastCheckMinute.get()) {
    lastCheckMinute.set(currentMinute);
    checkTasks(now);
}
```

教学版先按"分钟级"思考就足够，因为大多数 cron 任务本来就不是为了卡秒执行。

## 最小实现

### 第一步：允许创建一条调度记录

```java
public String create(String cronExpr, String prompt, boolean recurring, boolean durable) {
    String taskId = UUID.randomUUID().toString().substring(0, 8);
    double createdAt = System.currentTimeMillis() / 1000.0;

    Map<String, Object> task = new LinkedHashMap<>();
    task.put("id", taskId);
    task.put("cron", cronExpr);
    task.put("prompt", prompt);
    task.put("recurring", recurring);
    task.put("durable", durable);
    task.put("createdAt", createdAt);

    // recurring 任务添加抖动偏移（Java 特有机制）
    if (recurring) {
        task.put("jitter_offset", computeJitter(cronExpr));
    }

    tasks.add(task);
    if (durable) {
        saveDurable();  // 落盘到 .claude/scheduled_tasks.json
    }
    return "Created task " + taskId + " (" + mode + ", " + store + "): cron=" + cronExpr;
}
```

### 第二步：写一个定时检查循环

```java
private void checkLoop() {
    while (!stopRequested.get()) {
        LocalDateTime now = LocalDateTime.now();
        int currentMinute = now.getHour() * 60 + now.getMinute();

        // 每分钟只检查一次，避免重复触发
        if (currentMinute != lastCheckMinute.get()) {
            lastCheckMinute.set(currentMinute);
            checkTasks(now);
        }

        try {
            TimeUnit.SECONDS.sleep(1);  // 每秒唤醒一次
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
}
```

后台线程每秒唤醒，但通过 `lastCheckMinute` 去重，保证同一分钟内不会重复触发。

### 第三步：时间到了就发通知

```java
private void checkTasks(LocalDateTime now) {
    for (Map<String, Object> task : snapshot) {
        // 自动过期检查：recurring 任务超过 7 天（Java 特有）
        double ageDays = (nowEpoch - createdAt) / 86400.0;
        if (recurring && ageDays > AUTO_EXPIRY_DAYS) {
            expired.add(taskId);
            continue;
        }

        // 应用抖动偏移（Java 特有）
        LocalDateTime checkTime = now;
        if (jitter_offset > 0) {
            checkTime = now.minusMinutes(jitter_offset);
        }

        // cron 匹配检查
        if (cronMatches(cron, checkTime)) {
            String notification = "[Scheduled task " + taskId + "]: " + prompt;
            notificationQueue.add(notification);
            task.put("last_fired", nowEpoch);

            // 一次性任务触发后标记删除
            if (!recurring) {
                firedOneShots.add(taskId);
            }
        }
    }

    // 清理过期和已完成的一次性任务
    if (!expired.isEmpty() || !firedOneShots.isEmpty()) {
        tasks.removeIf(t -> removeIds.contains(t.get("id")));
        saveDurable();
    }
}
```

### 第四步：主循环像处理后台通知一样处理定时通知

```java
// agentLoop 中，在每次 LLM 调用之前
List<String> notifications = scheduler.drainNotifications();
for (String note : notifications) {
    paramsBuilder.addUserMessage(note);
}

// 正常调用 LLM
Message response = client.messages().create(paramsBuilder.build());
```

这样一来，定时任务最终还是由模型接手继续做。

## 为什么这章放在后台任务之后

因为这两章解决的问题很接近，但不是同一件事。

可以这样区分：

| 机制 | 回答的问题 |
|---|---|
| 后台任务 | "已经启动的慢操作，结果什么时候回来？" |
| 定时调度 | "一件事应该在未来什么时候开始？" |

这个顺序对初学者很友好。

因为先理解"异步结果回来"，再理解"未来触发一条新意图"，心智会更顺。

## 初学者最容易犯的错

### 1. 一上来沉迷 cron 语法细节

这章最容易跑偏到一大堆表达式规则。

但教学主线其实不是"背语法"，而是：

**调度记录如何进入通知队列，又如何回到主循环。**

### 2. 没有 `last_fired`

没有这个字段，系统很容易在短时间内重复触发同一条任务。

Java 实现通过 `lastCheckMinute`（`AtomicInteger`）在检查循环层面防止同一分钟重复触发，并在任务级别记录 `last_fired` 时间戳。

### 3. 只放内存，不支持落盘

如果用户希望"明天再提醒我"，程序一重启就没了，这就不是真正的调度。

Java 实现提供了 `durable` 模式，将任务保存到 `.claude/scheduled_tasks.json`，启动时自动加载。

### 4. 把调度触发结果直接在后台默默执行

教学主线里更清楚的做法是：

- 时间到了
- 先发通知（推入 `ConcurrentLinkedQueue`）
- 再让主循环决定怎么处理

这样系统行为更透明，读者也更容易理解。

### 5. 误以为定时任务必须绝对准点

很多初学者会把调度想成秒表。

但这里更重要的是"有计划地触发"，而不是追求毫秒级精度。

Java 实现的抖动机制（`computeJitter`）甚至刻意让整点任务偏移 1-4 分钟，避免所有任务在精确的 `:00` 或 `:30` 集中触发。

## 如何接到整个系统里

到了这一章，系统已经有两条重要的"外部事件输入"：

- 后台任务完成通知（`LinkedBlockingQueue`，s13）
- 定时调度触发通知（`ConcurrentLinkedQueue`，s14）

二者最好的统一方式是：

**都走通知队列，再在下一次模型调用前统一注入。**

这样主循环结构不会越来越乱。

Java 实现中，`CronLock`（PID 文件锁）还确保了同一时间只有一个调度器实例在运行，防止多个终端同时启动导致重复触发。

## 教学边界

这一章先讲清一条主线就够了：

**调度器做的是"记住未来"，不是"取代主循环"。**

所以教学版先只需要让读者看清：

- schedule record 负责记住未来何时开工
- 真正执行工作时，仍然回到任务系统和通知队列
- 它只是多了一种"开始入口"，不是多了一条新的主循环

Java 实现有两个教学范围之外但值得了解的增强：

1. **抖动机制（`computeJitter`）**：recurring 任务如果指向精确的 `:00` 或 `:30`，会基于 cron 表达式哈希值确定性偏移 1-4 分钟，避免整点扎堆。
2. **自动过期（7 天）**：recurring 任务创建超过 7 天后自动清理，防止调度表无限膨胀。

多进程锁（`CronLock`）、漏触发补报、自然语言时间语法这些，都应该排在这条主线之后。

## 一句话记住

**后台任务是在"等结果"，定时调度是在"等开始"。**

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S14CronScheduler"
```

可以试试这些任务：

1. 输入 `创建一个每分钟执行的定时任务，检查当前时间并报告`，观察它是否会按时进入通知队列。
2. 创建一个一次性（one-shot）任务，确认触发后是否会自动消失。
3. 创建一个 durable 任务，退出程序后重启，检查持久化的调度记录是否还在。
4. 输入 `/cron` 查看所有调度任务。
5. 输入 `/test` 手动触发一条测试通知，观察它如何注入对话。
