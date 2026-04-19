# s13：后台任务

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12 > [ s13 ] > s14 > s15 > s16 > s17 > s18 > s19`

> *慢命令可以在旁边等，主循环不必陪着发呆。*

## 这一章要解决什么问题

前面几章里，工具调用基本都是：

```text
模型发起
  ->
立刻执行
  ->
立刻返回结果
```

这对短命令没有问题。
但一旦遇到这些慢操作，就会卡住：

- `mvn clean install`
- `gradle build`
- `docker build`
- 大型代码生成或检查任务

如果主循环一直同步等待，会出现两个坏处：

- 模型在等待期间什么都做不了
- 用户明明还想继续别的工作，却被整轮流程堵住

所以这一章要解决的是：

**把"慢执行"移到后台，让主循环继续推进别的事情。**

## 建议联读

- 如果你还没有彻底稳住"任务目标"和"执行槽位"是两层对象，先看 [s13a-runtime-task-model.md](./s13a-runtime-task-model.md)。
- 如果你开始分不清哪些状态该落在 `RuntimeTaskRecord`、哪些还应留在任务板，回看 [data-structures.md](./data-structures.md)。
- 如果你开始把后台执行理解成"另一条主循环"，先看 [s02b-tool-execution-runtime.md](./s02b-tool-execution-runtime.md)，重新校正"并行的是执行与等待，不是主循环本身"。

## 先把几个词讲明白

### 什么叫前台

前台指的是：

> 主循环这轮发起以后，必须立刻等待结果的执行路径。

### 什么叫后台

后台不是神秘系统。
后台只是说：

> 命令先在另一条执行线上跑，主循环先去做别的事。

在 Java 实现里，这条"另一条执行线"就是 Virtual Thread（虚拟线程）。它是 Java 21 引入的轻量级线程，创建成本极低，不需要手动管理线程池。

### 什么叫通知队列

通知队列就是一条"稍后再告诉主循环"的收件箱。

后台任务完成以后，不是直接把全文硬塞回模型，
而是先写一条摘要通知，等下一轮再统一带回去。

Java 实现使用 `LinkedBlockingQueue<Map<String, String>>` 作为线程安全的通知队列。

## 最小心智模型

这一章最关键的句子是：

**主循环仍然只有一条，并行的是等待，不是主循环本身。**

可以把结构画成这样：

```text
主循环
  |
  +-- background_run("mvn install")
  |      -> 立刻返回 task_id
  |
  +-- 继续别的工作
  |
  +-- 下一轮模型调用前
         -> drain()
         -> 把摘要注入 messages

后台执行线 (Virtual Thread)
  |
  +-- 真正执行 mvn install
  +-- 完成后写入 LinkedBlockingQueue
```

如果读者能牢牢记住这张图，后面扩展成更复杂的异步系统也不会乱。

## 关键数据结构

### 1. RuntimeTaskRecord

```java
// 每个后台任务在 tasks Map 中维护的状态
Map<String, Object> task = Map.of(
    "status",    "running",      // running / completed / error
    "command",   "mvn install",  // 正在跑什么命令
    "result",    "",             // 执行结果
    "startedAt", System.currentTimeMillis()  // 什么时候开始
);
// key 是自动生成的 8 位 task_id，例如 "a1b2c3d4"
```

这些字段分别表示：

- `task_id`：唯一标识（UUID 前 8 位）
- `command`：正在跑什么命令
- `status`：运行中、完成、错误
- `startedAt`：什么时候开始
- `result`：执行结果（完成后填充）

Java 实现用 `ConcurrentHashMap<String, Map<String, Object>>` 管理所有任务状态，保证多线程安全。

### 2. Notification

```java
// 完成后写入通知队列的摘要
Map<String, String> notification = Map.of(
    "task_id", "a1b2c3d4",
    "status",  "completed",
    "result",  "BUILD SUCCESS"  // 预览，截断到 500 字符
);
```

通知只负责做一件事：

> 告诉主循环"有结果回来了，你要不要看"。

它不是完整日志本体。

## 最小实现

### 第一步：登记后台任务

```java
static class BackgroundManager {
    private final Map<String, Map<String, Object>> tasks
        = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Map<String, String>> notifications
        = new LinkedBlockingQueue<>();
```

这里最少要有两块状态：

- `tasks`：当前有哪些后台任务（`ConcurrentHashMap` 保证线程安全）
- `notifications`：哪些结果已经回来，等待主循环领取（`LinkedBlockingQueue` 天然线程安全）

### 第二步：启动后台执行线

Java 21 的 Virtual Thread 让"另一条执行线"变得极简：

```java
String run(String command, int timeout) {
    String taskId = UUID.randomUUID().toString().substring(0, 8);
    tasks.put(taskId, new ConcurrentHashMap<>(Map.of(
        "status", "running",
        "command", command,
        "result", "",
        "startedAt", System.currentTimeMillis()
    )));
    // 关键：Virtual Thread，轻量且非阻塞
    Thread.ofVirtual().name("bg-" + taskId).start(() -> exec(taskId, command, timeout));
    return "Background task " + taskId + " started: " + command;
}
```

这一步最重要的不是线程本身，而是：

**主循环拿到 `task_id` 后就可以先继续往前走。**

注意 `Thread.ofVirtual()` 的用法 —— 它不需要 `Executors`，不需要线程池配置，一行就启动了一个轻量级的虚拟线程。

### 第三步：完成后写通知

```java
private void exec(String taskId, String command, int timeout) {
    try {
        Process p = new ProcessBuilder("bash", "-c", command)
            .directory(WORKDIR.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(p.getInputStream().readAllBytes()).trim();
        if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            output = "Error: Timeout (" + timeout + "s)";
        }
        if (output.length() > MAX_OUTPUT)
            output = output.substring(0, MAX_OUTPUT);
        tasks.get(taskId).putAll(Map.of("status", "completed", "result", output));
    } catch (Exception e) {
        tasks.get(taskId).putAll(Map.of("status", "error", "result", e.getMessage()));
    }
    // 通知队列只放摘要（截断到 500 字符）
    String preview = ((String) tasks.get(taskId).get("result"));
    notifications.offer(Map.of(
        "task_id", taskId,
        "status",  (String) tasks.get(taskId).get("status"),
        "result",  preview != null ? preview.substring(0, Math.min(500, preview.length())) : ""
    ));
}
```

这里体现的思想很重要：

**后台执行负责产出结果，通知队列负责把结果送回主循环。**

### 第四步：下一轮前排空通知

```java
// 在 agentLoop 的每次 LLM 调用之前
var notifs = bg.drain();
if (!notifs.isEmpty()) {
    var sb = new StringBuilder("<background-results>\n");
    for (var n : notifs) {
        sb.append("[bg:").append(n.get("task_id")).append("] ")
          .append(n.get("status")).append(": ")
          .append(n.get("result")).append("\n");
    }
    sb.append("</background-results>");
    String notifText = sb.toString();
    // 注入通知为 user + assistant 消息对
    paramsBuilder.addUserMessage(notifText);
    paramsBuilder.addAssistantMessage("Noted background results.");
}
```

这样模型在下一轮就会知道：

- 哪个后台任务完成了
- 是成功、失败还是超时
- 如果要看全文，该再去调用 `read_file`

`drain()` 方法还有一个重要的优化 —— **通知折叠**：同一个 `task_id` 可能产生多条通知（例如先 running，后 completed），`drain()` 会用 `LinkedHashMap` 按插入顺序去重，只保留每个 `task_id` 的最后一条。

## 为什么完整输出不要直接塞回 prompt

这是本章必须讲透的点。

如果后台任务输出几万行日志，你不能每次都把全文塞回上下文。
更稳的做法是：

1. 完整输出写磁盘（或保留在内存 tasks 表中）
2. 通知里只放简短摘要（截断到 500 字符）
3. 模型真的要看全文时，再调用 `read_file` 或 `check_background`

这背后的心智很重要：

**通知负责提醒，文件负责存原文。**

## 如何接到主循环里

从 `s13` 开始，主循环多出一个标准前置步骤：

```text
1. 先排空通知队列 (drain)
2. 再调用模型
3. 普通工具照常同步执行
4. 如果模型调用 background_run，就登记后台任务并立刻返回 task_id
5. 下一轮再把后台结果带回模型
```

教学版最小工具建议先做两个：

- `background_run` —— 立刻返回 task_id，命令在 Virtual Thread 中后台执行
- `check_background` —— 检查任务状态，task_id 为 null 时列出所有

这样已经足够支撑最小异步执行闭环。

## 这一章和任务系统的边界

这是本章最容易和 `s12` 混掉的地方。

### `s12` 的 task 是什么

`s12` 里的 `task` 是：

> 工作目标

它关心的是：

- 要做什么
- 谁依赖谁
- 现在总体进度如何

### `s13` 的 background task 是什么

本章里的后台任务是：

> 正在运行的执行单元

它关心的是：

- 哪个命令正在跑
- 跑到什么状态
- 结果什么时候回来

所以最稳的记法是：

- `task` 更像工作板
- `background task` 更像运行中的作业

两者相关，但不是同一个东西。

## 初学者最容易犯的错

### 1. 以为"后台"就是更复杂的主循环

不是。
主循环仍然尽量保持单主线。

### 2. 只开线程，不登记状态

这样任务一多，你根本不知道：

- 谁还在跑
- 谁已经完成
- 谁失败了

Java 实现中，`BackgroundManager` 用 `ConcurrentHashMap` 维护了每个任务的状态，这样随时可以查询。

### 3. 把长日志全文塞进上下文

上下文很快就会被撑爆。Java 实现中，通知只携带截断到 500 字符的预览，完整输出保存在 tasks 表里（上限 50000 字符）。

### 4. 把 `s12` 的工作目标和本章的运行任务混为一谈

这会让后面多 agent 和调度章节全部打结。

## 教学边界

这一章只需要先把一个最小运行时模式讲清楚：

- 慢工作在后台跑（Virtual Thread）
- 主循环继续保持单主线
- 结果通过通知路径在后面回到模型

只要这条模式稳了，线程池、更多 worker 类型、更复杂的事件系统都可以后补。

Java 实现还额外加了**停滞检测**（`detectStalled`）：运行超过 45 秒的任务会被自动标记为超时错误并通知主循环。这是处理"僵尸任务"的实用机制，但不是核心教学内容。

这章真正要让读者守住的是：

**并行的是等待与执行槽位，不是主循环本身。**

## 学完这一章，你应该真正掌握什么

学完以后，你应该能独立复述下面几句话：

1. 主循环只有一条，并行的是等待，不是主循环本身。
2. 后台任务至少需要"任务表 + 通知队列"两块状态（`ConcurrentHashMap` + `LinkedBlockingQueue`）。
3. `background_run` 应该立刻返回 `task_id`，而不是同步卡住（用 `Thread.ofVirtual().start()`）。
4. 通知只放摘要，完整输出放文件。

如果这 4 句话都已经非常清楚，说明你已经掌握了后台任务系统的核心。

## 下一章学什么

这一章解决的是：

> 慢命令如何在后台运行。

下一章 `s14` 要解决的是：

> 如果连"启动后台任务"这件事都不一定由当前用户触发，而是由时间触发，该怎么做。

也就是从"异步运行"继续走向"定时触发"。

## 试一试

### 启动

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S13BackgroundTasks"
```

启动时确认 `.runtime-tasks/` 目录被自动创建。

### 案例 1：后台启动 + 立即返回 task_id

> 让 agent 在后台运行一个慢命令，验证 background_run 立即返回。

```
在后台运行 "mvn dependency:resolve"
```

观察要点：
- 模型调用 `> background_run:` 工具，返回类似 `Background task a1b2c3d4 started: mvn dependency:resolve (output_file=.runtime-tasks/a1b2c3d4.log)`
- 返回是**立即的**，不需要等待 mvn 执行完毕
- `.runtime-tasks/` 目录下生成了 `a1b2c3d4.json`（任务记录，status=running）和 `a1b2c3d4.log`（输出文件，暂时为空）

### 案例 2：并行工作 + 通知 drain 回注

> 在后台任务运行期间继续做别的工作，观察下一轮 LLM 调用前通知被注入。

紧接案例 1，趁后台运行时继续提问：

```
帮我列出 src/main/java/com/example/agent/sessions/ 下的文件
```

观察要点：
- 模型正常调用 `> bash:` 或 `> read_file:` 完成文件列表任务，**不被后台命令阻塞**
- 如果后台任务在这期间完成，日志出现黄色通知：`[bg:a1b2c3d4] completed: BUILD SUCCESS ... (output_file=.runtime-tasks/a1b2c3d4.log)`
- 这是 `drain()` 机制在工作：每次 LLM 调用前，从通知队列取出结果并注入 `<background-results>` 标签
- `.runtime-tasks/a1b2c3d4.log` 文件现在包含完整输出

### 案例 3：check_background 查询状态

> 用 check_background 分别查询单个任务和全部任务，验证不同返回格式。

先查询特定任务：

```
检查后台任务 a1b2c3d4 的状态
```

观察要点：
- 模型调用 `> check_background:` 传入 task_id
- 单任务返回 **JSON 格式**，包含 `id`、`status`、`command`、`result_preview`、`output_file` 字段
- 如果任务已完成，`status` 为 `"completed"`，`result_preview` 是压缩空白后的摘要

再查询全部：

```
列出所有后台任务
```

观察要点：
- 模型调用 `> check_background:` 不传 task_id（或传 null）
- 列表格式：`taskId: [status] command -> result_preview`
- 已完成的任务显示预览摘要，运行中的显示 `(running)`

### 案例 4：磁盘持久化 + 输出文件验证

> 验证后台任务的磁盘持久化结构，理解"通知负责提醒，文件负责存原文"的设计。

```
在后台运行 "mvn help:effective-pom"，等它完成后读取输出文件
```

观察要点：
- `background_run` 返回 `output_file=.runtime-tasks/XXXX.log`
- 后台任务完成后，通知中包含 `(output_file=...)` 路径
- 模型可调用 `> read_file:` 读取 `.runtime-tasks/XXXX.log` 查看完整输出（不受 500 字符预览限制）
- `.runtime-tasks/XXXX.json` 中 `status` 为 `"completed"`，`result_preview` 是压缩后的摘要，`finished_at` 有值
- 这验证了三层分离：通知（短摘要）→ 任务记录（JSON 元数据）→ 输出文件（完整原文）
