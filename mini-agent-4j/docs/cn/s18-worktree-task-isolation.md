# s18: Worktree + Task Isolation (Worktree 任务隔离)

`s00 > s01 > s02 > s03 > s04 > s05 > s06 > s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > [ s18 ] > s19`

> *任务板解决"做什么"，worktree 解决"在哪做而不互相踩到"。*

## 这一章要解决什么问题

到 `s17` 为止，系统已经可以：

- 拆任务
- 认领任务
- 让多个 agent 并行推进不同工作

但如果所有人都在同一个工作目录里改文件，很快就会出现这些问题：

- 两个任务同时改同一个文件
- 一个任务还没做完，另一个任务的修改已经把目录污染了
- 想单独回看某个任务的改动范围时，很难分清

也就是说，任务系统已经回答了"谁做什么"，却还没有回答：

**每个任务应该在哪个独立工作空间里执行。**

这就是 worktree 要解决的问题。

## 建议联读

- 如果你开始把 task、runtime slot、worktree lane 三层混成一个词，先看 [`team-task-lane-model.md`](./team-task-lane-model.md)。
- 如果你想确认 worktree 记录和任务记录分别该保存哪些字段，回看 [`data-structures.md`](./data-structures.md)。
- 如果你想从"参考仓库主干"角度确认这一章为什么必须晚于 tasks / teams，再看 [`s00e-reference-module-map.md`](./s00e-reference-module-map.md)。

## 先解释几个名词

### 什么是 worktree

如果你熟悉 git，可以把 worktree 理解成：

> 同一个仓库的另一个独立检出目录。

如果你还不熟悉 git，也可以先把它理解成：

> 一条属于某个任务的独立工作车道。

### 什么叫隔离执行

隔离执行就是：

> 任务 A 在自己的目录里跑，任务 B 在自己的目录里跑，彼此默认不共享未提交改动。

### 什么叫绑定

绑定的意思是：

> 把某个任务 ID 和某个 worktree 记录明确关联起来。

## 最小心智模型

最容易理解的方式，是把这一章拆成两张表：

```text
任务板
  负责回答：做什么、谁在做、状态如何

worktree 注册表
  负责回答：在哪做、目录在哪、对应哪个任务
```

两者通过 `task_id` 连起来：

```text
.tasks/task_12.json
  {
    "id": 12,
    "subject": "Refactor auth flow",
    "status": "in_progress",
    "owner": "alice",
    "worktree": "auth-refactor"
  }

.worktrees/index.json
  {
    "worktrees": [
      {
        "name": "auth-refactor",
        "path": ".worktrees/auth-refactor",
        "branch": "wt/auth-refactor",
        "task_id": 12,
        "status": "active"
      }
    ]
  }
```

看懂这两条记录，这一章的主线就已经抓住了：

**任务记录工作目标，worktree 记录执行车道。**

## 关键数据结构

### 1. TaskRecord 不再只记录 `worktree`

到当前教学代码这一步，任务记录里和车道相关的字段已经不只一个：

```java
var task = new LinkedHashMap<String,Object>();
task.put("id", 12);
task.put("subject", "Refactor auth flow");
task.put("status", "in_progress");
task.put("owner", "alice");
task.put("worktree", "auth-refactor");     // 当前绑定哪条车道
```

通过 `bindWorktree` 方法更新：

```java
private static synchronized String bindWorktree(int id, String worktree, String owner) {
    var task = loadTask(id);
    task.put("worktree", worktree);
    if (owner != null && !owner.isEmpty()) task.put("owner", owner);
    if ("pending".equals(task.get("status"))) task.put("status", "in_progress");
    saveTask(task);
    // ...
}
```

这里值得注意的关键点是：

- `worktree`：当前绑定着哪条车道
- `status`：任务状态在绑定时可能从 `pending` 变成 `in_progress`
- `owner`：谁在做

这三个字段回答不同问题，不能混成一个。

### 2. WorktreeRecord 不只是路径映射

```java
// createWorktree 内部
var entry = new LinkedHashMap<String,Object>();
entry.put("name", name);              // "auth-refactor"
entry.put("path", path.toString());   // ".worktrees/auth-refactor"
entry.put("branch", branch);          // "wt/auth-refactor"
entry.put("task_id", taskId);         // 12
entry.put("status", "active");        // active / kept / removed
entry.put("created_at", System.currentTimeMillis()/1000.0);
```

保存到 `.worktrees/index.json` 后大致是：

```json
{
    "name": "auth-refactor",
    "path": ".worktrees/auth-refactor",
    "branch": "wt/auth-refactor",
    "task_id": 12,
    "status": "active",
    "created_at": 1710000000.0
}
```

这里也要特别注意：

worktree 记录回答的不只是"目录在哪"，还开始回答：

- 什么分支
- 绑定到哪个任务
- 现在是 active、kept 还是 removed

这就是为什么这章讲的是：

**可观察的执行车道**

而不只是"多开一个目录"。

### 3. CloseoutRecord

这一章在当前代码里，收尾动作通过两个方法实现：

```java
// keepWorktree：保留车道，方便继续追看
private static String keepWorktree(String name) {
    item.put("status", "kept");
    item.put("kept_at", System.currentTimeMillis()/1000.0);
    // ...
}

// removeWorktree：回收车道，同时可完结对应任务
private static String removeWorktree(String name, boolean force, boolean completeTask) {
    // git worktree remove
    item.put("status", "removed");
    item.put("removed_at", System.currentTimeMillis()/1000.0);
    if (completeTask && wt.get("task_id") != null) {
        updateTask(tid, "completed", null);
        unbindWorktree(tid);
    }
    // ...
}
```

这层记录很重要，因为它把"结尾到底发生了什么"显式写出来，而不是靠人猜：

- 是保留目录，方便继续追看
- 还是回收目录，表示这条执行车道已经结束

### 4. EventRecord

```java
// emitEvent 内部
var payload = new LinkedHashMap<String,Object>();
payload.put("event", event);       // "worktree.create.after"
payload.put("ts", System.currentTimeMillis()/1000.0);
payload.put("task", task);         // {id: 12, status: "in_progress"}
payload.put("worktree", worktree); // {name: "auth-refactor", path: "..."}
if (error != null) payload.put("error", error);
Files.writeString(eventsPath, MAPPER.writeValueAsString(payload) + "\n",
    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
```

事件写入 `.worktrees/events.jsonl`，每条大致是：

```json
{
    "event": "worktree.create.after",
    "ts": 1710000001.0,
    "task": {"id": 12},
    "worktree": {"name": "auth-refactor", "path": ".worktrees/auth-refactor", "status": "active"}
}
```

为什么还要事件记录？

因为 worktree 的生命周期经常跨很多步：

- create.before / create.after / create.failed
- remove.before / remove.after / remove.failed
- keep

有显式事件日志，会比只看当前状态更容易排查问题。

## 最小实现

### 第一步：先有任务，再有 worktree

不要先开目录再回头补任务。

更清楚的顺序是：

1. 先创建任务
2. 再为这个任务分配 worktree

```java
// task_create 工具
var task = new LinkedHashMap<String,Object>();
task.put("id", nextTaskId);
task.put("subject", subject);
task.put("status", "pending");
task.put("owner", "");
task.put("worktree", "");
saveTask(task);

// worktree_create 工具（带 task_id 绑定）
createWorktree("auth-refactor", taskId=12, baseRef="HEAD");
```

### 第二步：创建 worktree 并写入注册表

```java
private static String createWorktree(String name, Integer taskId, String baseRef) {
    Path path = worktreeDir.resolve(name);
    String branch = "wt/" + name;
    String ref = baseRef != null ? baseRef : "HEAD";

    // 1. 写事件日志
    emitEvent("worktree.create.before", ...);

    // 2. 执行 git worktree add
    runGit("worktree", "add", "-b", branch, path.toString(), ref);

    // 3. 写入注册表
    var entry = new LinkedHashMap<String,Object>();
    entry.put("name", name);
    entry.put("path", path.toString());
    entry.put("branch", branch);
    entry.put("task_id", taskId);
    entry.put("status", "active");
    var index = loadIndex();
    ((List<Map<String,Object>>)index.get("worktrees")).add(entry);
    saveIndex(index);

    // 4. 如果有 task_id，同时绑定到任务
    if (taskId != null) bindWorktree(taskId, name, "");

    // 5. 写成功事件日志
    emitEvent("worktree.create.after", ...);
}
```

### 第三步：同时更新任务记录，不只是写一个 `worktree`

```java
private static synchronized String bindWorktree(int id, String worktree, String owner) {
    var task = loadTask(id);
    task.put("worktree", worktree);
    if (owner != null && !owner.isEmpty()) task.put("owner", owner);
    if ("pending".equals(task.get("status"))) task.put("status", "in_progress");
    saveTask(task);
    // ...
}
```

为什么这一步很关键？

因为如果只更新 worktree 注册表，不更新任务记录，系统就无法从任务板一眼看出"这个任务在哪个隔离目录里做"。

### 第四步：显式进入车道，再在对应目录里执行命令

当前代码里，运行命令时通过 `ProcessBuilder.directory()` 指定 worktree 目录：

```java
private static String worktreeRun(String name, String command) {
    var wt = findWorktree(name);
    Path path = Path.of((String) wt.get("path"));

    ProcessBuilder pb = System.getProperty("os.name").toLowerCase().contains("win")
        ? new ProcessBuilder("cmd", "/c", command)
        : new ProcessBuilder("bash", "-c", command);
    pb.directory(path.toFile());  // 关键：cwd 切到 worktree 目录
    pb.redirectErrorStream(true);
    Process p = pb.start();
    // ...
}
```

`pb.directory(path.toFile())` 这一行看起来普通，但它正是隔离的核心：

**同一个命令，在不同 `cwd` 里执行，影响范围就不一样。**

同时还有一个 `worktree_status` 工具，可以查看某条车道当前的 git 状态：

```java
private static String worktreeStatus(String name) {
    var wt = findWorktree(name);
    Path path = Path.of((String) wt.get("path"));
    ProcessBuilder pb = new ProcessBuilder("git", "status", "--short", "--branch");
    pb.directory(path.toFile());  // 在 worktree 目录里执行 git status
    // ...
}
```

### 第五步：收尾时显式走 closeout 动作

不要让收尾是隐式的。

当前代码提供了两个明确的收尾分支：

```java
// 保留：worktree_keep
private static String keepWorktree(String name) {
    item.put("status", "kept");
    item.put("kept_at", System.currentTimeMillis()/1000.0);
    emitEvent("worktree.keep", ...);
}

// 回收：worktree_remove
private static String removeWorktree(String name, boolean force, boolean completeTask) {
    runGit("worktree", "remove", "--force", path);
    item.put("status", "removed");
    item.put("removed_at", System.currentTimeMillis()/1000.0);
    if (completeTask) {
        updateTask(tid, "completed", null);
        unbindWorktree(tid);
    }
    emitEvent("worktree.remove.after", ...);
}
```

这样读者会更容易理解：

- 收尾一定要选动作（keep 或 remove）
- remove 时可以选择是否同时完结对应任务
- 收尾会同时回写任务记录、车道记录和事件日志

教学主线最好先把：

> `keep` 和 `remove` 看成同一个 closeout 决策的两个分支

这样读者心智会更顺。

## 为什么 `worktree_state` 和 `status` 要分开

这也是一个很容易被忽略的细点。

很多初学者会想：

> "任务有 `status` 了，为什么还要 worktree 的 `status`？"

因为这两个状态根本不是一层东西：

- 任务 `status` 回答：这件工作现在是 `pending`、`in_progress` 还是 `completed`
- worktree `status` 回答：这条执行车道现在是 `active`、`kept` 还是 `removed`

举个最典型的例子：

```text
任务已经 completed
  但 worktree 仍然 kept
```

这完全可能，而且很常见。
比如你已经做完了，但还想保留目录给 reviewer 看。

在 Java 实现里，`removeWorktree` 的 `completeTask` 参数就是控制这层关系：

```java
removeWorktree("auth-refactor", force=false, completeTask=true)
// → worktree 变 removed，任务变 completed

removeWorktree("auth-refactor", force=false, completeTask=false)
// → worktree 变 removed，任务状态不变
```

所以：

**任务状态和车道状态不能混成一个字段。**

## 为什么 worktree 不是"只是一个 git 小技巧"

很多初学者第一次看到这一章，会觉得：

> "这不就是多开几个目录吗？"

这句话只说对了一半。

真正关键的不只是"多开目录"，而是：

**把任务和执行目录做显式绑定，让并行工作有清楚的边界。**

如果没有这层绑定，系统仍然不知道：

- 哪个目录属于哪个任务
- 收尾时该完成哪条任务
- 崩溃后该恢复哪条关系

Java 实现里，这层绑定通过三个机制建立：

1. `createWorktree` 时传入 `task_id`
2. `bindWorktree` 在任务记录里写入 worktree 名称
3. `removeWorktree` 时通过 `task_id` 找到并更新对应任务

## 如何接到前面章节里

这章和前面几章是强耦合的：

- `s12` 提供任务 ID
- `s15-s17` 提供队友和认领机制
- `s18` 则给这些任务提供独立执行车道

把三者连起来看，会变成：

```text
任务被创建
  ->
队友认领任务
  ->
系统为任务分配 worktree
  ->
命令在对应目录里执行
  ->
任务完成时决定保留还是删除 worktree
```

这条链一旦建立，多 agent 并行工作就会清楚很多。

## worktree 不是任务本身，而是任务的执行车道

这句话值得单独再说一次。

很多读者第一次学到这里时，会把这两个词混着用：

- task
- worktree

但它们回答的其实不是同一个问题：

- task：做什么
- worktree：在哪做

所以更完整、也更不容易混的表达方式是：

- 工作图任务
- worktree 执行车道

如果你开始分不清：

- 任务
- 运行时任务
- worktree

建议回看：

- [`team-task-lane-model.md`](./team-task-lane-model.md)
- [`s13a-runtime-task-model.md`](./s13a-runtime-task-model.md)
- [`entity-map.md`](./entity-map.md)

## 初学者最容易犯的错

### 1. 有 worktree 注册表，但任务记录里没有 `worktree`

这样任务板就丢掉了最重要的一条执行信息。

Java 实现里 `bindWorktree` 会同时更新两边。

### 2. 有任务 ID，但命令仍然在主目录执行

如果 `ProcessBuilder.directory()` 没切到 worktree 路径，隔离形同虚设。

Java 实现里 `worktreeRun` 通过 `pb.directory(path.toFile())` 确保命令在正确目录执行。

### 3. 只会 `worktree_remove`，不会解释 closeout 的含义

这样读者最后只记住"删目录"这个动作，却不知道系统真正想表达的是：

- 保留
- 回收
- 为什么这么做
- 是否同时完结对应任务

### 4. 删除 worktree 前不看未提交改动

这是最危险的一类错误。

教学版也应该至少先建立一个原则：

**删除前先检查是否有脏改动。**

Java 实现里提供了 `worktree_status` 工具，可以在删除前查看当前状态。

### 5. 没有 worktree status / closeout 这类显式收尾状态

这样系统就会只剩下"现在目录还在不在"，而没有：

- 这条车道最后怎么收尾
- 是主动保留还是主动删除

Java 实现里 worktree 记录有 `status` 字段（active / kept / removed）和时间戳。

### 6. 把 worktree 当成长期垃圾堆

如果从不清理，目录会越来越多，状态越来越乱。

### 7. 没有事件日志

一旦创建失败、删除失败或任务关系错乱，没有事件日志会很难排查。

Java 实现里每次 worktree 操作都会通过 `emitEvent` 写入 `.worktrees/events.jsonl`。

## 教学边界

这章先要讲透的不是所有 worktree 运维细节，而是主干分工：

- task 记录"做什么"
- worktree 记录"在哪做"
- create / run / closeout 串起这条隔离执行车道

只要这条主干清楚，教学目标就已经达成。

崩溃恢复、删除安全检查、全局缓存区、非 git 回退这些，都应该放在这条主干之后。

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S18WorktreeIsolation"
```

可以试试这些任务：

1. 为两个不同任务各建一个 worktree，观察 `/tasks` 和 `/worktrees` 的对应关系。
2. 分别在两个 worktree 里运行 `git status`（用 `worktree_status`），感受目录隔离。
3. 删除一个 worktree（`worktree_remove` 带 `complete_task=true`），确认对应任务被正确收尾。
4. 保留一个 worktree（`worktree_keep`），确认它的状态变为 `kept` 而任务不变。
5. `/events` 查看完整生命周期事件日志。

读完这一章，你应该能自己说清楚这句话：

**任务系统管"做什么"，worktree 系统管"在哪做且互不干扰"。**
