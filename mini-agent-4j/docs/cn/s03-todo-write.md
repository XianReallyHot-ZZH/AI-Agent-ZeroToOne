# s03：TodoWrite

`s01 > s02 > [ s03 ] s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *"没有计划的 Agent 会迷失方向。"* —— 用结构化待办列表强制问责。
>
> **装置层**：nag 提醒 —— 让模型始终保持在正轨上。

## 问题

多步骤任务会悄悄失败。模型在处理第 5 步时忘了第 3 步。没有可见的进度追踪，模型（和你）都无法判断哪些完成了、哪些还没做。你需要一个结构化计划，让模型在工作过程中持续更新。

## 方案

```
+--------+      +-------+      +---------+
|  用户   | ---> |  LLM  | ---> | 分发表  |
+--------+      +---+---+      +----+----+
                    ^               |
                    |               v
              +-----+----+   +------------+
              | TodoMgr  |   | 其他工具   |
              | [ ] 任务1 |   +------------+
              | [>] 任务2 |
              | [x] 任务3 |
              +-----+----+
                    ^
                    | <reminder>（如果连续 3 轮未更新 todo，注入提醒）
```

`TodoManager` 执行一个简单的状态机：每个事项是 `pending`（待处理）、`in_progress`（进行中）或 `completed`（已完成）。同一时刻只能有一个 `in_progress`。如果模型太久不更新 todo，就注入一条提醒。

## 原理

1. **定义 `todo` 工具**，拥有结构化 schema。每个事项包含 `id`、`text` 和 `status`（枚举：`pending`、`in_progress`、`completed`）。

```java
AgentLoop.defineTool("todo",
    "Update task list. Track progress on multi-step tasks.",
    Map.of("items", Map.of(
        "type", "array",
        "items", Map.of(
            "type", "object",
            "properties", Map.of(
                "id",     Map.of("type", "string"),
                "text",   Map.of("type", "string"),
                "status", Map.of("type", "string",
                    "enum", List.of("pending", "in_progress", "completed"))),
            "required", List.of("id", "text", "status")))),
    List.of("items"))
```

2. **TodoManager 验证并存储完整列表。** 最多 20 个事项，最多 1 个 `in_progress`。`render()` 生成可视化输出：

```java
TodoManager todo = new TodoManager();
// 更新后：
// [ ] 搭建项目结构
// [>] 创建数据库 schema    ← 进行中
// [ ] 编写 API 端点
// [x] 安装依赖             ← 已完成
```

3. **nag 提醒（概念）。** 如果模型连续 3 轮以上没调用 `todo`，向对话注入提醒：

```
<reminder>Update your todos. You haven't updated the task list recently.</reminder>
```

这迫使模型对自己的计划负责。

4. **注册处理函数** —— 解析事项列表并委托给 TodoManager：

```java
dispatcher.register("todo", input -> {
    List<?> items = (List<?>) input.get("items");
    return todo.update(items);
});
```

## 变更对比

| 组件          | s02                 | s03                               |
|---------------|---------------------|-----------------------------------|
| 工具          | 4 个（bash、read、write、edit） | +1：`todo`               |
| 状态追踪      | （无）              | `TodoManager`（内存中）           |
| Nag 提醒      | （无）              | 空闲 3 轮后注入                    |
| Agent 循环    | 不变                | 不变                               |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S03TodoWrite"
```

1. `创建一个包含 3 个源文件的项目，用 todo 工具追踪你的进度`
2. `重构项目使其使用包结构 —— 先做计划，再执行`
3. `为项目搭建测试套件，把每个测试文件作为 todo 事项追踪`
