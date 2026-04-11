# s18：Worktree + 任务隔离

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > [ s18 ] s19`

> *"按目录隔离，按任务 ID 协调。文件系统即隔离边界。"* —— 并行 Agent，零文件冲突。
>
> **装置层**：Worktree —— 绑定到任务的目录级执行上下文。

## 问题

s17 的自治 Teammate 都在同一个目录下工作。如果 alice 和 bob 同时编辑 `UserService.java`，后写入的会覆盖前一个人的修改。你需要目录级隔离，让并行 Agent 能在同一仓库上工作而不产生文件冲突。

## 方案

```
控制平面（Tasks）              执行平面（Worktrees）
+-------------+                +---------------------+
| Task #1     | ----绑定--->   | wt/auth-refactor    |
| Task #2     | ----绑定--->   | wt/fix-tests        |
| Task #3     |                |   （未认领）         |
+-------------+                +---------------------+
       |                               |
  .tasks/task_1.json          .worktrees/wt-auth-refactor/
  .tasks/task_2.json             （git worktree = 独立检出）
  .tasks/task_3.json             （自己的分支，自己的工作目录）

绑定关系：task_id <-> worktree 名称
生命周期：active -> kept | removed
事件流：  .worktrees/events.jsonl（结构化审计日志）
```

任务是"控制平面"（做什么）。Worktree 是"执行平面"（在哪做）。它们通过任务 ID 绑定。

## 原理

1. **WorktreeManager 封装 git worktree 操作：

```java
WorktreeManager wtMgr = new WorktreeManager(repoRoot);

// 自动检测 git 仓库根目录
Path repoRoot = detectRepoRoot();
// 执行：git rev-parse --show-toplevel
```

2. **创建 worktree**（可选绑定到任务）：

```java
dispatcher.register("worktree_create", input ->
    wtMgr.create(
        (String) input.get("name"),       // "wt-auth-refactor"
        input.get("task_id") instanceof Number n ? n.intValue() : null,  // 可选绑定
        (String) input.get("base_ref")    // 可选："main"、"HEAD~3"
    )
);
// 执行：git worktree add .worktrees/wt-auth-refactor -b wt-auth-refactor
```

3. **在 worktree 内执行命令：**

```java
dispatcher.register("worktree_run", input ->
    wtMgr.run(
        (String) input.get("name"),       // "wt-auth-refactor"
        (String) input.get("command")     // "mvn test"
    )
);
// 执行：cd .worktrees/wt-auth-refactor && <command>
```

4. **生命周期管理** —— 保留或删除：

```java
// 删除 worktree（删除目录 + 分支）
dispatcher.register("worktree_remove", input ->
    wtMgr.remove((String) input.get("name"), force, false)
);
// 执行：git worktree remove .worktrees/wt-auth-refactor

// 保留 worktree（留待后续审查）
dispatcher.register("worktree_keep", input -> ...)
```

5. **事件流**用于审计追踪：

```java
dispatcher.register("worktree_events", input ->
    wtMgr.recentEvents(limit)
);
// 来自 .worktrees/events.jsonl 的输出：
// [2024-01-15T10:30:00] create.before: wt-auth-refactor (task_id=1)
// [2024-01-15T10:30:01] create.after: wt-auth-refactor created
// [2024-01-15T10:45:00] run: wt-auth-refactor "mvn test"
// [2024-01-15T11:00:00] remove.before: wt-auth-refactor
```

6. **REPL 命令：**

```
/tasks      -- 展示任务池
/worktrees  -- 列出所有 worktree 及状态
/events     -- 展示最近的 worktree 生命周期事件
```

## 变更对比

| 组件          | s17                 | s18                               |
|---------------|---------------------|-----------------------------------|
| 隔离          | 同一目录            | 每个任务一个 git worktree          |
| 新工具        | （无）              | `worktree_create/list/status/run/remove/keep/events`（7 个工具） |
| 绑定          | （无）              | Task ID <-> Worktree 名称         |
| 事件追踪      | （无）              | `.worktrees/events.jsonl`         |
| 新增类        | （无）              | `WorktreeManager`                 |
| 仓库检测      | （无）              | `detectRepoRoot()` 通过 git CLI   |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S18WorktreeIsolation"
```

1. `创建一个任务 "重构认证模块" 并创建绑定的 worktree`
2. `再创建一个任务 "修复测试失败" 并创建自己的 worktree`
3. `在第一个 worktree 里运行 "ls"`
4. `/worktrees`（列出所有 worktree 及其状态）
5. `审查完第一个 worktree 的修改后删除它`
6. `/events`（查看生命周期事件日志）
