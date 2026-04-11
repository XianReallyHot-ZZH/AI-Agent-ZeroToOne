# s12：任务系统

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > [ s12 ] s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *"状态存在对话之外 —— 因为它在文件系统上。"* —— 任务能经受压缩、重启甚至崩溃。
>
> **装置层**：任务图（DAG） —— 持久化的目标，寿命超越任何一次对话。

## 问题

s03 的 `TodoManager` 是内存中的。当对话被压缩（s06）或进程重启时，待办列表就消失了。你需要持久化到磁盘的任务，支持依赖关系（任务 B 不能在任务 A 完成前开始），并能经受任何意外。

## 方案

```
.tasks/
  task_1.json   { id:1, subject:"搭建数据库", status:"completed",
                  blockedBy:[], blocks:[2] }
  task_2.json   { id:2, subject:"编写 API", status:"in_progress",
                  blockedBy:[1], blocks:[3] }
  task_3.json   { id:3, subject:"添加测试", status:"pending",
                  blockedBy:[2], blocks:[] }

  完成 task_1 --> 自动清除 task_2.blockedBy
  完成 task_2 --> 自动清除 task_3.blockedBy
```

每个任务是磁盘上的一个 JSON 文件。依赖关系构成 DAG。完成任务时自动解除后续任务的阻塞。

## 原理

1. **Task record** —— Java 21 record 作为不可变数据载体：

```java
public record Task(
    int id, String subject, String description,
    String status, String owner,
    List<Integer> blockedBy, List<Integer> blocks
) {}
```

2. **TaskManager 将任务持久化为 `.tasks/` 目录下的独立 JSON 文件：

```java
TaskManager taskMgr = new TaskManager(workDir.resolve(".tasks"));

// 创建任务
taskMgr.create("Setup database", "Create schema and seed data");
// 返回："Created task #1: Setup database [pending]"

// 创建有依赖的任务
taskMgr.create("Write API", "CRUD endpoints for users");
// 返回："Created task #2: Write API [pending]"
```

3. **四个工具**将任务操作暴露给模型：

```java
// task_create —— 新建任务，含主题 + 描述
dispatcher.register("task_create", input ->
    taskMgr.create((String) input.get("subject"),
                   (String) input.getOrDefault("description", "")));

// task_update —— 修改状态，添加依赖
dispatcher.register("task_update", input ->
    taskMgr.update(taskId, status, addBlockedBy, addBlocks));

// task_list —— 所有任务的可视化摘要
dispatcher.register("task_list", input -> taskMgr.listAll());

// task_get —— 单个任务的完整详情
dispatcher.register("task_get", input -> taskMgr.get(taskId));
```

4. **依赖自动解除。** 当 `task_update` 将状态设为 `completed` 时，TaskManager 自动将已完成任务从其他所有任务的 `blockedBy` 列表中移除：

```java
// TaskManager.update() 内部：
if ("completed".equals(status)) {
    _clearDependency(taskId);  // 从所有 blockedBy 数组中移除
}
```

5. **为什么用文件存储？** `.tasks/` 目录能经受上下文压缩（s06）、进程重启甚至崩溃。模型随时可以通过 `task_list` 恢复完整状态。

## 变更对比

| 组件          | s11                 | s12                               |
|---------------|---------------------|-----------------------------------|
| 状态存储      | 内存中（TodoManager） | 基于文件 `.tasks/` JSON         |
| 依赖关系      | （无）              | `blockedBy` / `blocks` DAG       |
| 持久性        | 重启后丢失          | 经受重启和压缩                     |
| 工具          | 7 个                | +4：`task_create/update/list/get` |
| 新增类        | （无）              | `Task`（record）、`TaskManager`  |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S12TaskSystem"
```

1. `创建一个任务计划：搭建项目、编写代码、添加测试（含依赖关系）`
2. `展示任务列表`
3. `把第一个任务标记为已完成`
4. `现在哪些任务被解除阻塞了？`
