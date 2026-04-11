# s17：自治 Agent

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > [ s17 ] s18 > s19`

> *"自治 = 有工作就做，没工作就等。Lead 只管发任务，不管分配。"* —— Teammate 自己找活干。
>
> **装置层**：idle 轮询 + 任务认领 —— 自己发现工作的 Teammate。

## 问题

s16 的 Teammate 等待明确指令。Lead 必须手动分配每个任务："alice，做任务 1。bob，做任务 2。"这不可扩展。你希望 Teammate 能从共享任务池中*拉取*任务，而不是等着被*推送*任务。

## 方案

```
Lead:                    任务池（.tasks/）              Teammate：
  |                     +-----------+                       |
  |-- task_create --->  | Task #1   | [pending, 无 owner]   |
  |-- task_create --->  | Task #2   | [pending, 无 owner]   |
  |                     +-----------+                       |
  |                          |                              |
  |                    （Teammate 轮询）                      |
  |                          |                              |
  |                     +----+----+                         |
  |                     |         |                         |
  |                  alice      bob                        |
  |                  认领 #1    认领 #2                     |
  |                (owner=alice) (owner=bob)                |
  |                                                       |
  |                  [执行]       [执行]                    |
  |                  completed    completed                |
```

Lead 创建任务。Teammate 自主轮询 `.tasks/` 目录寻找未认领的工作并主动认领。

## 原理

1. **任务工具在 Lead 和 Teammate 之间共享。** Lead 创建任务；Teammate 认领并更新：

```java
// Lead 创建任务
dispatcher.register("task_create", input ->
    taskMgr.create((String) input.get("subject"),
                   (String) input.getOrDefault("description", "")));

// Teammate 列出任务以发现可用工作
dispatcher.register("task_list", input -> taskMgr.listAll());
```

2. **`idle` 工具触发轮询逻辑。** 当 Teammate 无事可做时调用 `idle`，检查未认领的任务：

```java
dispatcher.register("idle", input -> {
    String taskList = taskMgr.listAll();
    if (taskList.contains("[ ]")) {  // [ ] = pending，未认领
        return "There are pending tasks available. Use claim_task to pick one.\n"
            + taskList;
    }
    return "No pending tasks. Waiting...";
});
```

3. **`claim_task` 工具设置所有权和状态：

```java
dispatcher.register("claim_task", input ->
    taskMgr.claim(
        ((Number) input.get("task_id")).intValue(),
        (String) input.get("owner"))
);
// 设置：owner = "alice"，status = "in_progress"
```

4. **Teammate 循环有两个阶段**（在 TeamManager 中）：

```
WORK 阶段：  执行已分配的任务，将状态更新为 completed
IDLE 阶段：  调用 idle 工具 -> 轮询 pending 任务 -> claim_task -> 回到 WORK
```

5. **REPL 命令**用于可观测性：

```
/team    -- 列出所有队友
/inbox   -- 检查 Lead 的收件箱
/tasks   -- 展示任务池状态
```

## 变更对比

| 组件          | s16                 | s17                               |
|---------------|---------------------|-----------------------------------|
| 任务认领      | 手动分配            | 自主（Teammate 轮询 + 认领）      |
| 新工具        | （无）              | `idle`、`claim_task`              |
| 任务工具      | （无）              | `task_create`、`task_list`、`task_get`、`task_update` |
| Teammate 行为 | 等待指令            | 轮询工作 + 自动认领               |
| REPL 命令     | `/team`、`/inbox`   | +1：`/tasks`                      |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S17AutonomousAgents"
```

1. `生成两个队友：alice（backend）和 bob（testing）`
2. `创建任务："编写 UserService"、"为 UserService 添加单元测试"、"编写集成测试"`
3. `/tasks`（观察 Teammate 自主认领任务）
4. `/inbox`（检查 Teammate 的进度消息）
