# s17: Autonomous Agents (自治智能体)

`s00 > s01 > s02 > s03 > s04 > s05 > s06 > s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > [ s17 ] > s18 > s19`

> *一个团队真正开始"自己运转"，不是因为 agent 数量变多，而是因为空闲的队友会自己去找下一份工作。*

## 这一章要解决什么问题

到了 `s16`，团队已经有：

- 持久队友
- 邮箱
- 协议
- 任务板

但还有一个明显瓶颈：

**很多事情仍然要靠 lead 手动分配。**

例如任务板上已经有 10 条可做任务，如果还要 lead 一个个点名：

- Alice 做 1
- Bob 做 2
- Charlie 做 3

那团队规模一大，lead 就会变成瓶颈。

所以这一章要解决的核心问题是：

**让空闲队友自己扫描任务板，找到可做的任务并认领。**

## 建议联读

- 如果你开始把 teammate、task、runtime slot 三层一起讲糊，先回 [`team-task-lane-model.md`](./team-task-lane-model.md)。
- 如果你读到"auto-claim"时开始疑惑"活着的执行槽位"到底放在哪，继续看 [`s13a-runtime-task-model.md`](./s13a-runtime-task-model.md)。
- 如果你开始忘记"长期队友"和"一次性 subagent"最根本的区别，回看 [`entity-map.md`](./entity-map.md)。

## 先解释几个名词

### 什么叫自治

这里的自治，不是完全没人管。

这里说的自治是：

> 在提前给定规则的前提下，队友可以自己决定下一步接哪份工作。

### 什么叫认领

认领，就是把一条原本没人负责的任务，标记成"现在由我负责"。

### 什么叫空闲阶段

空闲阶段不是关机，也不是消失。

它表示：

> 这个队友当前手头没有活，但仍然活着，随时准备接新活。

## 最小心智模型

最清楚的理解方式，是把每个队友想成在两个阶段之间切换：

```text
WORK
  |
  | 当前轮工作做完，或者主动进入 idle
  v
IDLE
  |
  +-- 看邮箱，有新消息 -> 回到 WORK
  |
  +-- 看任务板，有 ready task -> 认领 -> 回到 WORK
  |
  +-- 长时间什么都没有 -> shutdown
```

这里的关键不是"让它永远不停想"，而是：

**空闲时，按规则检查两类新输入：邮箱和任务板。**

在 Java 实现里，这两个阶段通过虚拟线程事件循环串联：

```java
while (true) {
    // WORK PHASE: 标准 agent 循环，最多 50 轮
    for (int round = 0; round < 50; round++) {
        // ... agent loop，拦截 idle 工具后 break
    }
    // IDLE PHASE: 轮询收件箱和未认领任务
    for (int p = 0; p < polls; p++) {
        Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
        // 检查收件箱 -> 有消息则 resume
        // 扫描未认领任务 -> 有可做任务则认领并 resume
    }
    // 超时则 shutdown
}
```

## 关键数据结构

### 1. Claimable Predicate

和 `s12` 一样，这里最重要的是：

**什么任务算"当前这个队友可以安全认领"的任务。**

在 Java 教学代码里，`scanUnclaimedTasks(role)` 的判定不是单纯看 `pending`，而是：

```java
private static List<Map<String, Object>> scanUnclaimedTasks(String role) {
    // 对 .tasks/ 下每个 task 文件：
    // 1. status == "pending"          → 任务还没开始
    // 2. owner 为空                   → 还没人认领
    // 3. blockedBy 为空               → 没有前置阻塞
    // 4. required_role 匹配或为空     → 当前队友角色满足认领策略
}
```

这 4 个条件缺一不可：

- 任务还没开始
- 还没人认领
- 没有前置阻塞
- 当前队友角色满足认领策略

最后一条很关键。

因为现在任务可以带 `required_role` 字段，例如：

```json
{
    "id": 7,
    "subject": "Implement login page",
    "status": "pending",
    "owner": "",
    "required_role": "frontend"
}
```

这表示：

> 这条任务不是"谁空着谁就拿"，而是要先过角色条件。

### 2. 认领后的任务记录

一旦认领成功，任务记录至少会发生这些变化：

```java
// claimTask 内部：
var t = MAPPER.readValue(Files.readString(p), Map.class);
t.put("owner", owner);        // "alice"
t.put("status", "in_progress");
Files.writeString(p, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(t));
```

保存到 `.tasks/task_7.json` 后大致是：

```json
{
    "id": 7,
    "subject": "Implement login page",
    "owner": "alice",
    "status": "in_progress"
}
```

系统开始不只是知道"任务现在有人做了"，还开始知道：

- 这是谁拿走的
- 任务状态从 `pending` 变成了 `in_progress`

### 3. Claim Event Log

除了回写任务文件，事件日志记录了认领动作的完整上下文。

在 Java 实现里，claim 动作通过事件机制可追溯：

```text
.tasks/task_7.json         → 当前谁在做什么
事件日志（通过 agent 输出可见） → 什么时候、谁、怎么拿走的
```

为什么这层日志重要？

因为它回答的是"自治系统刚刚做了什么"。

只看最终任务文件，你知道的是：

- 现在是谁 owner

而看事件日志，你才能知道：

- 它是什么时候被拿走的
- 是谁拿走的
- 是空闲时自动拿走，还是手动调用 `claim_task`

### 4. Durable Request Record

这章虽然重点是自治，但它**不能从 `s16` 退回到"协议请求只放内存里"**。

所以 Java 代码里仍然保留了持久化请求记录：

```text
.team/requests/{request_id}.json  (概念上的持久化)
```

实际实现里，`shutdownRequests` 和 `planRequests` 用 `ConcurrentHashMap` 在内存中跟踪，同时通过 `MessageBus` 写入 `.team/inbox/` 的 JSONL 文件做持久化。

它保存的是：

- shutdown request
- plan approval request
- 对应的状态更新

这层边界很重要，因为自治队友并不是在"脱离协议系统另起炉灶"，而是：

> 在已有团队协议之上，额外获得"空闲时自己找活"的能力。

### 5. 身份块

当上下文被压缩后，队友有时会"忘记自己是谁"。

最小补法是重新注入一段身份提示：

```java
// 身份再注入 —— 在认领任务后、恢复工作前执行
paramsBuilder.addUserMessage(
    "<identity>You are '" + name + "', role: " + role
    + ", team: " + teamName + ".</identity>"
);
paramsBuilder.addAssistantMessage("I am " + name + ". Continuing.");
```

这样做的目的不是好看，而是为了让恢复后的下一轮继续知道：

- 我是谁
- 我的角色是什么
- 我属于哪个团队

## 最小实现

### 第一步：让队友拥有 WORK -> IDLE 的循环

```java
// teammateLoop 的主循环
while (true) {
    // ---- WORK PHASE: 标准 agent 循环，最多 50 轮 ----
    boolean idleRequested = false;
    for (int round = 0; round < 50; round++) {
        // ... agent loop 处理
        // 拦截 idle 工具 → idleRequested = true; break
    }

    // ---- IDLE PHASE: 轮询收件箱和未认领任务 ----
    setMemberStatus(name, "idle");
    boolean resume = false;
    // ...
    if (!resume) { setMemberStatus(name, "shutdown"); return; }
    setMemberStatus(name, "working");
}
```

### 第二步：在 IDLE 里先看邮箱

```java
// 检查收件箱
var inbox = bus.readInbox(name);
if (!inbox.isEmpty()) {
    for (var msg : inbox) {
        if ("shutdown_request".equals(msg.get("type"))) {
            setMemberStatus(name, "shutdown"); return;
        }
        paramsBuilder.addUserMessage(MAPPER.writeValueAsString(msg));
    }
    resume = true; break;
}
```

这一步的意思是：

如果有人明确找我，那我优先处理"明确发给我的工作"。

### 第三步：如果邮箱没消息，再按"当前角色"扫描可认领任务

```java
// 扫描未认领任务（按角色过滤）
var unclaimed = scanUnclaimedTasks(role);
if (!unclaimed.isEmpty()) {
    var task = unclaimed.get(0);
    int taskId = ((Number) task.get("id")).intValue();
    claimTask(taskId, name);
    // ...
}
```

这里当前代码有两个很关键的升级：

- `scanUnclaimedTasks(role)` 不是无差别扫任务，而是带着角色过滤
- `claimTask(taskId, name)` 把认领动作原子地写进任务文件

也就是说，自治不是"空闲了就乱抢一条"，而是：

> 按当前队友的角色、任务状态和阻塞关系，挑出一条真正允许它接手的工作。

### 第四步：认领后先补身份，再把任务提示塞回主循环

```java
// 身份再注入
paramsBuilder.addUserMessage(
    "<identity>You are '" + name + "', role: " + role
    + ", team: " + teamName + ".</identity>"
);
paramsBuilder.addAssistantMessage("I am " + name + ". Continuing.");
// 任务提示注入
paramsBuilder.addUserMessage(
    "<auto-claimed>Task #" + task.get("id") + ": "
    + task.get("subject") + "\n"
    + task.getOrDefault("description", "") + "</auto-claimed>"
);
paramsBuilder.addAssistantMessage(
    "Claimed task #" + taskId + ". Working on it."
);
resume = true; break;
```

这一步非常关键。

因为"认领成功"本身还不等于"队友真的能顺利继续"。

还必须把两件事接回上下文里：

- 身份上下文（`<identity>` 标签）
- 新任务提示（`<auto-claimed>` 标签）

只有这样，下一轮 `WORK` 才不是无头苍蝇，而是：

> 带着明确身份和明确任务恢复工作。

### 第五步：长时间没事就退出

```java
int polls = IDLE_TIMEOUT_SECONDS / Math.max(POLL_INTERVAL_SECONDS, 1);
for (int p = 0; p < polls; p++) {
    Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
    // 检查收件箱和任务板 ...
}
// 超时 → shutdown
if (!resume) { setMemberStatus(name, "shutdown"); return; }
```

为什么需要这个退出路径？

因为空闲队友不一定要永远占着资源。
教学版先做"空闲一段时间后关闭"就够了。

默认配置：

- `POLL_INTERVAL_SECONDS = 5`：每 5 秒轮询一次
- `IDLE_TIMEOUT_SECONDS = 60`：空闲 60 秒后自动 shutdown

## 为什么认领必须是原子动作

"原子"这个词第一次看到可能不熟。

这里它的意思是：

> 认领这一步要么完整成功，要么不发生，不能一半成功一半失败。

为什么？

因为两个队友可能同时扫描到同一个可做任务。

如果没有锁，就可能发生：

- Alice 看见任务 3 没主人
- Bob 也看见任务 3 没主人
- 两人都把自己写成 owner

所以 Java 实现里 `claimTask` 方法加了 `synchronized`：

```java
private static synchronized String claimTask(int taskId, String owner) {
    Path p = TASKS_DIR.resolve("task_" + taskId + ".json");
    if (!Files.exists(p)) return "Error: Task " + taskId + " not found";
    var t = MAPPER.readValue(Files.readString(p), Map.class);
    t.put("owner", owner);
    t.put("status", "in_progress");
    Files.writeString(p, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(t));
    return "Claimed task #" + taskId + " for " + owner;
}
```

`synchronized` 关键字确保同一时刻只有一个线程能执行认领逻辑。

## 身份重注入为什么重要

这是这章里一个很容易被忽视，但很关键的点。

当上下文压缩发生以后，队友可能丢掉这些关键信息：

- 我是谁
- 我的角色是什么
- 我属于哪个团队

如果没有这些信息，队友后续行为很容易漂。

所以 Java 实现里的做法是：

每次从 idle 恢复时，用 `<identity>` 标签重新注入身份：

```java
paramsBuilder.addUserMessage(
    "<identity>You are '" + name + "', role: " + role
    + ", team: " + teamName + ".</identity>"
);
paramsBuilder.addAssistantMessage("I am " + name + ". Continuing.");
```

这里你可以把它理解成一条恢复规则：

> 任何一次从 idle 恢复、或任何一次压缩后恢复，只要身份上下文可能变薄，就先补身份，再继续工作。

## 为什么 s17 不能从 s16 退回"内存协议"

这是一个很容易被漏讲，但其实非常重要的点。

很多人一看到"自治"，就容易只盯：

- idle
- auto-claim
- 轮询

然后忘了 `s16` 已经建立过的另一条主线：

- 请求必须可追踪
- 协议状态必须可恢复

所以现在 Java 教学代码里，像：

- shutdown request（`ConcurrentHashMap` + MessageBus 持久化）
- plan approval（同上）

仍然会通过消息总线写入 `.team/inbox/`。

也就是说，`s17` 不是推翻 `s16`，而是在 `s16` 上继续加一条新能力：

```text
协议系统继续存在
  +
自治扫描与认领开始存在
```

这两条线一起存在，团队才会像一个真正的平台，而不是一堆各自乱跑的 worker。

## 如何接到前面几章里

这一章其实是前面几章第一次真正"串起来"的地方：

- `s12` 提供任务板
- `s15` 提供持久队友
- `s16` 提供结构化协议
- `s17` 则让队友在没有明确点名时，也能自己找活

所以你可以把 `s17` 理解成：

**从"被动协作"升级到"主动协作"。**

## 自治的是"长期队友"，不是"一次性 subagent"

这层边界如果不讲清，读者很容易把 `s04` 和 `s17` 混掉。

`s17` 里的自治执行者，仍然是 `s15` 那种长期队友：

- 有名字
- 有角色
- 有邮箱
- 有 idle 阶段
- 可以反复接活

它不是那种：

- 接一条子任务
- 做完返回摘要
- 然后立刻消失

的一次性 subagent。

同样地，这里认领的也是：

- `s12` 里的工作图任务

而不是：

- `s13` 里的后台执行槽位

所以这章其实是在两条已存在的主线上再往前推一步：

- 长期队友
- 工作图任务

再把它们用"自治认领"连接起来。

如果你开始把下面这些词混在一起：

- teammate
- protocol request
- task
- runtime task

建议回看：

- [`team-task-lane-model.md`](./team-task-lane-model.md)
- [`s13a-runtime-task-model.md`](./s13a-runtime-task-model.md)

## 初学者最容易犯的错

### 1. 只看 `pending`，不看 `blockedBy`

如果一个任务虽然是 `pending`，但前置任务还没完成，它就不应该被认领。

### 2. 只看状态，不看 `required_role`

这会让错误的队友接走错误的任务。

教学版虽然简单，但从这一章开始，已经应该明确告诉读者：

- 并不是所有 ready task 都适合所有队友
- 角色条件本身也是 claim policy 的一部分

### 3. 没有认领锁

这会直接导致重复抢同一条任务。

Java 实现里用 `synchronized` 方法解决这个问题。

### 4. 空闲阶段只轮询任务板，不看邮箱

这样队友会错过别人明确发给它的消息。

Java 实现里先检查收件箱，再扫描未认领任务，邮箱优先级更高。

### 5. 认领了任务，但没有写 claim event

这样最后你只能看到"任务现在被谁做"，却看不到：

- 它是什么时候被拿走的
- 是自动认领还是手动认领

### 6. 队友永远不退出

教学版里，长时间无事可做时退出是合理的。
否则读者会更难理解资源何时释放。

Java 实现里通过 `IDLE_TIMEOUT_SECONDS = 60` 控制自动退出。

### 7. 上下文压缩后不重注入身份

这很容易让队友后面的行为越来越不像"它本来的角色"。

Java 实现里每次从 idle 恢复都会用 `<identity>` 标签重新注入身份信息。

## 教学边界

这一章先只把自治主线讲清楚：

**空闲检查 -> 安全认领 -> 恢复工作。**

只要这条链路稳了，读者就已经真正理解了"自治"是什么。

更细的 claim policy、公平调度、事件驱动唤醒、长期保活，都应该建立在这条最小自治链之后，而不是抢在前面。

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S17AutonomousAgents"
```

可以试试这些任务：

1. 先建几条 ready task，再生成两个队友（不同角色），观察它们是否会自动分工认领。
2. 建几条带 `required_role` 的任务，确认只有角色匹配的队友会认领。
3. 让某个队友进入 idle，再通过 `send_message` 发一条消息给它，观察它是否会重新被唤醒。
4. 同时创建多个任务，让多个队友并行认领，确认不会出现重复认领（`synchronized` 锁生效）。

这一章要建立的核心心智是：

**自治不是让 agent 乱跑，而是让它在清晰规则下自己接住下一份工作。**
