# s13a: Runtime Task Model (运行时任务模型)

> 这篇桥接文档专门解决一个非常容易混淆的问题：
>
> **任务板里的 task，和后台/队友/监控这些"正在运行的任务"，不是同一个东西。**

## 建议怎么联读

这篇最好夹在下面几份文档中间读：

- 先看 [`s12-task-system.md`](./s12-task-system.md)（`S12TaskSystem.java`），确认工作图任务在讲什么。
- 再看 [`s13-background-tasks.md`](./s13-background-tasks.md)（`S13BackgroundTasks.java`），确认后台执行在讲什么。
- 如果词开始混，再回 `s00` 系列的总览文档。
- 如果想把字段和状态彻底对上，再对照各章数据结构。

## 为什么必须单独讲这一篇

主线里：

- `s12` 讲的是任务系统
- `s13` 讲的是后台任务

这两章各自都没错。
但如果不额外补一层桥接，很多读者很快就会把两种"任务"混在一起。

例如：

- 任务板里的 "实现 auth 模块"
- 后台执行里的 "正在跑 mvn test"
- 队友执行里的 "alice 正在做代码改动"

这些都可以叫"任务"，但它们不在同一层。

为了让整个仓库接近满分，这一层必须讲透。

## 先解释两个完全不同的"任务"

### 第一种：工作图任务

这就是 `s12`（`S12TaskSystem.java`）里的任务板节点。

它回答的是：

- 要做什么
- 谁依赖谁
- 谁认领了
- 当前进度如何

它更像：

> 工作计划中的一个可跟踪工作单元。

### 第二种：运行时任务

这类任务回答的是：

- 现在有什么执行单元正在跑
- 它是什么类型
- 是在运行、完成、失败还是被杀掉
- 输出文件在哪

它更像：

> 系统当前活着的一条执行槽位。

## 最小心智模型

你可以先把两者画成两张表：

```text
工作图任务 (WorkGraphTask)
  - durable（持久化）
  - 面向目标与依赖
  - 生命周期更长

运行时任务 (RuntimeTask)
  - runtime（运行时）
  - 面向执行与输出
  - 生命周期更短
```

它们的关系不是"二选一"，而是：

```text
一个工作图任务
  可以派生
一个或多个运行时任务
```

例如：

```text
工作图任务：
  "实现 auth 模块"

运行时任务：
  1. 后台跑测试
  2. 启动一个 coder teammate
  3. 监控一个 MCP 服务返回结果
```

## 为什么这层区别非常重要

如果不区分这两层，后面很多章节都会开始缠在一起：

- `s13` 的后台任务会和 `s12` 的任务板混淆
- `s15`-`s17` 的队友任务会不知道该挂在哪
- `s18` 的 worktree 到底绑定哪一层任务，也会变模糊

所以你要先记住一句：

**工作图任务管"目标"，运行时任务管"执行"。**

## 关键数据结构

### 1. WorkGraphTaskRecord

这就是 `s12` 里的那条 durable task。在 Java 中用 record 表达：

```java
/**
 * 工作图任务记录 —— s12 任务板中的持久化工作节点。
 */
public record WorkGraphTaskRecord(
    int id,
    String subject,
    TaskStatus status,               // PENDING / IN_PROGRESS / COMPLETED
    List<Integer> blockedBy,
    List<Integer> blocks,
    String owner,                    // nullable
    String worktree                  // nullable
) {
    public enum TaskStatus {
        PENDING, IN_PROGRESS, COMPLETED
    }
}
```

### 2. RuntimeTaskState

教学版可以先用这个最小形状。在 Java 中用 record 表达：

```java
/**
 * 运行时任务状态 —— 系统当前活着的一条执行槽位。
 */
public record RuntimeTaskState(
    String id,                       // "b8k2m1qz"
    RuntimeTaskType type,
    RuntimeStatus status,
    String description,
    Instant startTime,
    Instant endTime,                 // nullable
    Path outputFile,                 // nullable，产出文件路径
    boolean notified
) {
    public enum RuntimeStatus {
        RUNNING, COMPLETED, FAILED, KILLED
    }
}
```

这里的字段重点在于：

- `type`：它是什么执行单元
- `status`：它现在在运行态还是终态
- `outputFile`：它的产出在哪
- `notified`：结果有没有回通知系统

### 3. RuntimeTaskType

你不必在教学版里一次性实现所有类型，
但应该让读者知道"运行时任务"是一个类型族，而不只是 `background shell` 一种。

最小类型表可以先这样讲。在 Java 中用 sealed interface 或 enum 表达：

```java
/**
 * 运行时任务类型 —— 运行时执行单元的类型族。
 * 使用 sealed interface 限定已知子类型。
 */
public sealed interface RuntimeTaskType
    permits LocalBash, LocalAgent, RemoteAgent, InProcessTeammate, Monitor, Workflow {

    record LocalBash() implements RuntimeTaskType {}
    record LocalAgent() implements RuntimeTaskType {}
    record RemoteAgent() implements RuntimeTaskType {}
    record InProcessTeammate() implements RuntimeTaskType {}
    record Monitor() implements RuntimeTaskType {}
    record Workflow() implements RuntimeTaskType {}
}
```

教学版也可以先用简单的 enum：

```java
public enum RuntimeTaskType {
    LOCAL_BASH,
    LOCAL_AGENT,
    REMOTE_AGENT,
    IN_PROCESS_TEAMMATE,
    MONITOR,
    WORKFLOW
}
```

## 最小实现

### 第一步：继续保留 `s12` 的任务板

这一层不要动。

### 第二步：单独加一个 RuntimeTaskManager

```java
/**
 * 运行时任务管理器 —— 管理所有当前活着的执行槽位。
 */
public class RuntimeTaskManager {
    private final Map<String, RuntimeTaskState> tasks = new ConcurrentHashMap<>();

    public RuntimeTaskState create(RuntimeTaskType type, String description) {
        String id = generateRuntimeId();
        RuntimeTaskState task = new RuntimeTaskState(
            id, type, RuntimeTaskState.RuntimeStatus.RUNNING,
            description, Instant.now(), null,
            Path.of(".task_outputs", id + ".txt"),
            false
        );
        tasks.put(id, task);
        return task;
    }

    public Optional<RuntimeTaskState> findById(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public void complete(String id) {
        tasks.computeIfPresent(id, (k, t) -> new RuntimeTaskState(
            t.id(), t.type(), RuntimeTaskState.RuntimeStatus.COMPLETED,
            t.description(), t.startTime(), Instant.now(),
            t.outputFile(), t.notified()
        ));
    }

    public void fail(String id) {
        tasks.computeIfPresent(id, (k, t) -> new RuntimeTaskState(
            t.id(), t.type(), RuntimeTaskState.RuntimeStatus.FAILED,
            t.description(), t.startTime(), Instant.now(),
            t.outputFile(), t.notified()
        ));
    }

    private static String generateRuntimeId() {
        return Long.toHexString(System.currentTimeMillis())
             + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000));
    }
}
```

### 第三步：后台运行时创建 runtime task

```java
/**
 * 生成后台 bash 运行时任务。
 */
public RuntimeTaskState spawnBashTask(String command, RuntimeTaskManager manager) {
    RuntimeTaskState task = manager.create(
        RuntimeTaskType.LOCAL_BASH, command
    );

    // 使用虚拟线程异步执行
    Thread.startVirtualThread(() -> {
        try {
            String output = BashTool.execute(
                Map.of("command", command), workDir
            );
            writeOutput(task.outputFile(), output);
            manager.complete(task.id());
        } catch (Exception e) {
            writeOutput(task.outputFile(), "Error: " + e.getMessage());
            manager.fail(task.id());
        }
    });

    return task;
}
```

### 第四步：必要时把 runtime task 关联回工作图任务

```java
/**
 * 关联扩展 —— 运行时任务与工作图任务的绑定。
 */
public record RuntimeTaskBinding(
    String runtimeTaskId,
    int workGraphTaskId
) {}

// 使用
RuntimeTaskState runtimeTask = manager.create(RuntimeTaskType.LOCAL_BASH, "mvn test");
bindings.put(runtimeTask.id(), new RuntimeTaskBinding(runtimeTask.id(), 12));
```

这一步不是必须一上来就做，但如果系统进入多 agent / worktree 阶段，就会越来越重要。

## 一张真正清楚的图

```text
Work Graph (s12)
  task #12: Implement auth module
        |
        +-- spawns runtime task A: LOCAL_BASH (mvn test)
        +-- spawns runtime task B: LOCAL_AGENT (coder worker)
        +-- spawns runtime task C: MONITOR (watch service status)

Runtime Task Layer (s13a)
  A/B/C each have:
  - own runtime ID
  - own status (RUNNING / COMPLETED / FAILED / KILLED)
  - own output file
  - own lifecycle
```

## 它和后面章节怎么连

这层一旦讲清楚，后面几章会顺很多：

- `s13`（`S13BackgroundTasks.java`）后台命令，本质上是 runtime task
- `s15`-`s17`（`S15AgentTeams.java` - `S17AutonomousAgents.java`）队友/agent，也可以看成 runtime task 的一种
- `s18`（`S18WorktreeIsolation.java`）worktree 主要绑定工作图任务，但也会影响运行时执行环境
- `s19`（`S19McpPlugin.java`）某些外部监控或异步调用，也可能落成 runtime task

所以后面只要你看到"有东西在后台活着并推进工作"，都可以先问自己两句：

- 它是不是某个 durable work graph task 派生出来的执行槽位。
- 它的状态是不是应该放在 runtime layer，而不是任务板节点里。

## 状态名称对比

两种任务有各自的状态名，最好不要混：

```text
工作图任务 (WorkGraphTask)          运行时任务 (RuntimeTask)
---------------------------------------------------------
PENDING                            RUNNING
IN_PROGRESS                        COMPLETED
COMPLETED                          FAILED
                                   KILLED
```

注意工作图任务的 `COMPLETED` 和运行时任务的 `COMPLETED` 虽然名字相同，但语义不同：

- 前者表示"目标已达成"
- 后者表示"执行单元正常结束"

## 初学者最容易犯的错

### 1. 把后台 shell 直接写成任务板状态

这样 durable task 和 runtime state 就混在一起了。

### 2. 认为一个工作图任务只能对应一个运行时任务

现实里很常见的是一个工作目标派生多个执行单元。

### 3. 用同一套状态名描述两层对象

例如：

- 工作图任务的 `PENDING / IN_PROGRESS / COMPLETED`
- 运行时任务的 `RUNNING / COMPLETED / FAILED / KILLED`

这两套状态最好不要混。

### 4. 忽略 outputFile 和 notified 这类运行时字段

工作图任务不太关心这些，运行时任务非常关心。

## 教学边界

这篇最重要的，不是把运行时字段一次加满，而是先把下面三层对象彻底拆开：

- durable task（`WorkGraphTaskRecord`）是长期工作目标
- runtime task（`RuntimeTaskState`）是当前活着的执行槽位
- notification / output 只是运行时把结果带回来的通道

运行时任务类型枚举、增量输出 offset、槽位清理策略，都可以等你先把这三层边界手写清楚以后再扩展。

## 一句话记住

**工作图任务管"长期目标和依赖"，运行时任务管"当前活着的执行单元和输出"。**

**`s12`（`S12TaskSystem.java`）的 task 是工作图节点，`s13+` 的 runtime task 是系统里真正跑起来的执行单元。**
