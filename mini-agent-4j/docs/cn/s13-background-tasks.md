# s13：后台任务

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12 > [ s13 ] s14 > s15 > s16 > s17 > s18 > s19`

> *"发出即忘 —— Agent 不会因等待命令而阻塞。"* —— Virtual Thread 让并行变得轻而易举。
>
> **装置层**：通知队列 —— 异步结果投递给单线程循环。

## 问题

有些命令要跑好几分钟：`mvn clean install`、`docker build`、`npm install`。如果 Agent 每次都阻塞等待，那在等待期间什么也做不了。你希望 Agent 能发射慢命令，继续思考，等结果就绪后再收取。

## 方案

```
Agent ----[background_run("mvn install")]----[background_run("npm install")]----[其他工作]----
              |                                    |
              v                                    v
         [Virtual Thread A]                  [Virtual Thread B]
              |                                    |
              +--> LinkedBlockingQueue <-- 完成通知 <--+
                                      |
                                      v
                              [下次 LLM 调用前 drain]
                              [将结果注入对话]
```

命令在 Java 21 Virtual Thread 中执行（轻量、非阻塞）。完成通知进入线程安全队列。Agent 循环在每次 LLM 调用前清空队列。

## 原理

1. **BackgroundManager 将命令执行包装在 Virtual Thread 中：

```java
BackgroundManager bg = new BackgroundManager(workDir);

// 立即返回 task_id
dispatcher.register("background_run", input ->
    bg.run((String) input.get("command"), 120)  // 120 秒超时
);
// 输出："Started bg task: mvn-1709123456 (running in background)"
```

2. **`bg.run()` 内部** —— 生成一个 Virtual Thread：

```java
public String run(String command, int timeout) {
    String taskId = generateTaskId(command);
    Thread.startVirtualThread(() -> {
        String result = BashTool.execute(command, workDir);
        notificationQueue.offer(Map.of(
            "task_id", taskId,
            "status", "completed",
            "result", result
        ));
    });
    return "Started bg task: " + taskId;
}
```

3. **每次 REPL 迭代前清空通知：**

```java
while (true) {
    var notifs = bg.drainNotifications();
    if (!notifs.isEmpty()) {
        for (var n : notifs) {
            System.out.println("[bg:" + n.get("task_id") + "] "
                + n.get("status") + ": " + n.get("result"));
        }
    }
    // ... 然后提示用户 ...
}
```

4. **用 `check_background` 检查状态：

```java
dispatcher.register("check_background", input ->
    bg.check((String) input.get("task_id")));
// 输出："mvn-1709123456: COMPLETED (exit 0, 12s)"
```

5. **循环保持单线程。** 只有子进程 I/O 通过 Virtual Thread 并行化。LLM 调用仍然是顺序的。

## 变更对比

| 组件          | s12                 | s13                               |
|---------------|---------------------|-----------------------------------|
| 执行模型      | 仅阻塞              | 阻塞 + 后台（Virtual Thread）     |
| 新工具        | （无）              | `background_run`、`check_background` |
| 通知机制      | （无）              | `LinkedBlockingQueue`             |
| 新增类        | `TaskManager`       | `BackgroundManager`               |
| REPL 钩子     | （无）              | 提示前 `drainNotifications()`     |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S13BackgroundTasks"
```

1. `在后台运行 "mvn dependency:resolve"`
2. `趁它运行的时候，列出项目文件`
3. `检查后台任务状态`
4. `在后台运行 "find . -name '*.java'" 并继续工作`
