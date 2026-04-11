# Team Task Lane Model (队友-任务-车道模型)

> 到了 `s15-s18`，读者最容易混掉的，不是某个类名，而是：
>
> **系统里到底是谁在工作、谁在协调、谁在记录目标、谁在提供执行目录。**

## 这篇桥接文档解决什么问题

如果你一路从 `s15` 看到 `s18`，脑子里很容易把下面这些词混在一起：

- teammate
- protocol request
- task
- runtime task
- worktree

它们都和"工作推进"有关。
但它们不是同一层。

如果这层边界不单独讲清，后面读者会经常出现这些困惑：

- 队友是不是任务本身？
- `requestId` 和 `taskId` 有什么区别？
- worktree 是不是后台任务的一种？
- 一个任务完成了，为什么 worktree 还能保留？

这篇就是专门用来把这几层拆开的。

## 建议怎么联读

最推荐的读法是：

1. 先看 [`s15-agent-teams.md`](./s15-agent-teams.md)，确认长期队友在讲什么。
2. 再看 [`s16-team-protocols.md`](./s16-team-protocols.md)，确认请求-响应协议在讲什么。
3. 再看 [`s17-autonomous-agents.md`](./s17-autonomous-agents.md)，确认自治认领在讲什么。
4. 最后看 [`s18-worktree-task-isolation.md`](./s18-worktree-task-isolation.md)，确认隔离执行车道在讲什么。

如果你开始混：

- 回 [`entity-map.md`](./entity-map.md) 看模块边界。
- 回 [`data-structures.md`](./data-structures.md) 看 Java record 结构。
- 回 [`s13a-runtime-task-model.md`](./s13a-runtime-task-model.md) 看"目标任务"和"运行时执行槽位"的差别。

## 先给结论

先记住这一组最重要的区分：

```text
teammate (TeamMember record)
  = 谁在长期参与协作

protocol request (RequestRecord record)
  = 团队内部一次需要被追踪的协调请求

task (TaskRecord record)
  = 要做什么

runtime task / execution slot (RuntimeTaskState record)
  = 现在有什么执行单元正在跑

worktree (WorktreeRecord record)
  = 在哪做，而且不和别人互相踩目录
```

这五层里，最容易混的是最后三层：

- `task`
- `runtime task`
- `worktree`

所以你必须反复问自己：

- 这是"目标"吗？
- 这是"执行中的东西"吗？
- 这是"执行目录"吗？

## 一张最小清晰图

```text
Team Layer
  teammate: alice (frontend)    -> TeamMember("alice", "frontend", IDLE)
  teammate: bob   (backend)     -> TeamMember("bob",   "backend",  WORKING)

Protocol Layer
  request_id=req_01             -> RequestRecord("req_01", PLAN_REVIEW, PENDING, ...)
  kind=plan_approval
  status=pending

Work Graph Layer
  task_id=12                    -> TaskRecord(12, "Implement login page", ...)
  subject="Implement login page"
  owner="alice"
  status="in_progress"

Runtime Layer
  runtime_id=rt_01              -> RuntimeTaskState("rt_01", IN_PROCESS_TEAMMATE, RUNNING, ...)
  type=in_process_teammate
  status=running

Execution Lane Layer
  worktree=login-page           -> WorktreeRecord("login-page", .worktrees/login-page, ...)
  path=.worktrees/login-page
  status=active
```

你可以看到：

- `alice` 不是任务
- `requestId` 不是任务
- `runtimeId` 也不是任务
- `worktree` 更不是任务

真正表达"这件工作本身"的，只有 `taskId=12` 那层。

## 1. Teammate：谁在长期协作

这是 `s15` 开始建立的层。

它回答的是：

- 这个长期 worker 叫什么
- 它是什么角色
- 它当前是 WORKING、IDLE 还是 SHUTDOWN
- 它有没有独立 inbox

最小 Java 形状：

```java
public record TeamMember(
    String name,
    String role,
    MemberStatus status
) {}

public enum MemberStatus {
    IDLE, WORKING, SHUTDOWN
}
```

运行时，每个 teammate 持有一个独立 virtual thread 事件循环和 inbox：

```java
public class TeammateRunner {
    private final TeamMember member;
    private final BlockingQueue<MessageEnvelope> inbox = new LinkedBlockingQueue<>();
    private Thread virtualThread;

    public void start() {
        virtualThread = Thread.startVirtualThread(this::eventLoop);
    }

    private void eventLoop() {
        while (member.status() != MemberStatus.SHUTDOWN) {
            try {
                MessageEnvelope envelope = inbox.take();
                handleMessage(envelope);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
```

这层的核心不是"又多开一个 agent"。

而是：

> 系统开始有长期存在、可重复接活、可被点名协作的身份。

## 2. Protocol Request：谁在协调什么

这是 `s16` 建立的层。

它回答的是：

- 有谁向谁发起了一个需要追踪的请求
- 这条请求是什么类型
- 它现在是 PENDING、APPROVED 还是 REJECTED

最小 Java 形状：

```java
public record RequestRecord(
    String requestId,
    RequestKind kind,
    RequestStatus status,
    String from,
    String to
) {}

public enum RequestKind {
    SHUTDOWN, PLAN_REVIEW
}

public enum RequestStatus {
    PENDING, APPROVED, REJECTED, EXPIRED
}
```

请求追踪器用 `ConcurrentHashMap` 管理所有活跃请求：

```java
public class RequestTracker {
    private final ConcurrentHashMap<String, RequestRecord> activeRequests =
        new ConcurrentHashMap<>();

    public RequestRecord createRequest(RequestKind kind, String from, String to) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        RequestRecord record = new RequestRecord(requestId, kind, RequestStatus.PENDING, from, to);
        activeRequests.put(requestId, record);
        return record;
    }

    public Optional<RequestRecord> find(String requestId) {
        return Optional.ofNullable(activeRequests.get(requestId));
    }
}
```

这一层不要和普通聊天混。

因为它不是"发一条消息就算完"，而是：

> 一条可以被继续更新、继续审核、继续恢复的协调记录。

## 3. Task：要做什么

这是 `s12` 的工作图任务，也是 `s17` 自治认领的对象。

它回答的是：

- 目标是什么
- 谁负责
- 是否有阻塞
- 当前进度如何

最小 Java 形状：

```java
public record TaskRecord(
    int id,
    String subject,
    String description,
    TaskStatus status,
    List<Integer> blockedBy,
    List<Integer> blocks,
    String owner,
    String worktree
) {}

public enum TaskStatus {
    PENDING, IN_PROGRESS, COMPLETED, DELETED
}
```

这层的关键词是：

**目标**

不是目录，不是协议，不是进程。

`TaskManager` 管理所有工作图任务：

```java
public class TaskManager {
    private final Map<Integer, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final Path tasksDir;  // .tasks/ 目录

    public List<TaskRecord> findUnblocked() {
        return tasks.values().stream()
            .filter(t -> t.status() == TaskStatus.PENDING)
            .filter(t -> t.blockedBy().isEmpty()
                || t.blockedBy().stream().allMatch(id ->
                    tasks.get(id).status() == TaskStatus.COMPLETED))
            .toList();
    }
}
```

## 4. Runtime Task / Execution Slot：现在有什么执行单元在跑

这一层在 `s13` 的桥接文档里已经单独解释过，但到了 `s15-s18` 必须再提醒一次。

比如：

- 一个后台 `mvn test` 正在跑
- 一个长期 teammate 正在工作
- 一个 monitor 正在观察外部状态

这些都更像：

> 正在运行的执行槽位

而不是"任务目标本身"。

最小 Java 形状：

```java
public record RuntimeTaskState(
    String id,
    RuntimeTaskType type,
    RuntimeTaskStatus status,
    String description,
    Instant startTime,
    Optional<Instant> endTime,
    Path outputFile,
    boolean notified,
    OptionalInt workGraphTaskId   // 关联的工作图任务 ID
) {}

public enum RuntimeTaskType {
    LOCAL_BASH, IN_PROCESS_TEAMMATE, MONITOR
}

public enum RuntimeTaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED
}
```

运行时任务管理器：

```java
public class RuntimeTaskManager {
    private final ConcurrentHashMap<String, RuntimeTaskState> activeSlots =
        new ConcurrentHashMap<>();

    public RuntimeTaskState register(RuntimeTaskType type, String description) {
        String id = generateId();
        RuntimeTaskState state = new RuntimeTaskState(
            id, type, RuntimeTaskStatus.RUNNING, description,
            Instant.now(), Optional.empty(), Path.of(".task_outputs/" + id + ".txt"),
            false, OptionalInt.empty()
        );
        activeSlots.put(id, state);
        return state;
    }

    public void complete(String id) {
        activeSlots.computeIfPresent(id, (k, v) -> new RuntimeTaskState(
            v.id(), v.type(), RuntimeTaskStatus.COMPLETED, v.description(),
            v.startTime(), Optional.of(Instant.now()), v.outputFile(),
            v.notified(), v.workGraphTaskId()
        ));
    }
}
```

这里最重要的边界是：

- 一个任务可以派生多个 runtime task
- 一个 runtime task 通常只是"如何执行"的一个实例

## 5. Worktree：在哪做

这是 `s18` 建立的执行车道层。

它回答的是：

- 这份工作在哪个独立目录里做
- 这条目录车道对应哪个任务
- 这条车道现在是 ACTIVE、KEPT 还是 REMOVED

最小 Java 形状：

```java
public record WorktreeRecord(
    String name,
    Path path,
    String branch,
    int taskId,
    WorktreeStatus status
) {}

public enum WorktreeStatus {
    ACTIVE, KEPT, REMOVED
}
```

创建和管理 worktree：

```java
public class WorktreeManager {
    private final Path worktreesDir;  // .worktrees/
    private final Map<String, WorktreeRecord> index = new ConcurrentHashMap<>();

    public WorktreeRecord create(String name, int taskId) {
        Path wtPath = worktreesDir.resolve(name);
        String branch = "wt/" + name;

        // 通过 ProcessBuilder 调用 git worktree add
        ProcessBuilder pb = new ProcessBuilder(
            "git", "worktree", "add", wtPath.toString(), "-b", branch
        );
        // ... 启动并等待完成

        WorktreeRecord record = new WorktreeRecord(
            name, wtPath, branch, taskId, WorktreeStatus.ACTIVE
        );
        index.put(name, record);
        return record;
    }
}
```

这层的关键词是：

**执行边界**

它不是工作目标本身，而是：

> 让这份工作在独立目录里推进的执行车道。

## 这五层怎么连起来

你可以把后段章节连成下面这条链：

```text
teammate (TeamMember)
  通过 protocol request (RequestRecord) 协调
  认领 task (TaskRecord)
  作为一个 runtime execution slot (RuntimeTaskState) 持续运行
  在某条 worktree lane (WorktreeRecord) 里改代码
```

如果写成 Java 对象关系，会变成：

```java
// alice 认领 task #12，在 login-page worktree 里工作
TeamMember alice = new TeamMember("alice", "frontend", MemberStatus.WORKING);
TaskRecord task12 = new TaskRecord(12, "Implement login page", "...",
    TaskStatus.IN_PROGRESS, List.of(), List.of(), "alice", "login-page");
RuntimeTaskState rt01 = new RuntimeTaskState("rt_01",
    RuntimeTaskType.IN_PROCESS_TEAMMATE, RuntimeTaskStatus.RUNNING,
    "alice working on login page", Instant.now(), Optional.empty(),
    Path.of(".task_outputs/rt_01.txt"), false, OptionalInt.of(12));
WorktreeRecord wt = new WorktreeRecord("login-page",
    Path.of(".worktrees/login-page"), "wt/login-page", 12, WorktreeStatus.ACTIVE);

// 关系链：alice -> task12 -> rt01 -> wt
// alice 是队友（谁在做）
// task12 是工作目标（做什么）
// rt01 是执行槽位（正在跑的实例）
// wt 是执行车道（在哪做）
```

## 一个最典型的混淆例子

很多读者会把这句话说成：

> "alice 就是在做 login-page 这个 worktree 任务。"

这句话把三层东西混成了一句：

- `alice`：队友（`TeamMember`）
- `login-page`：worktree（`WorktreeRecord`）
- "任务"：工作图任务（`TaskRecord`）

更准确的说法应该是：

> `alice` 认领了 `task #12`，并在 `login-page` 这条 worktree 车道里推进它。

一旦你能稳定地这样表述，后面几章就不容易乱。

## 初学者最容易犯的错

### 1. 把 teammate 和 task 混成一个对象

队友是执行者（`TeamMember`），任务是目标（`TaskRecord`）。

### 2. 把 `requestId` 和 `taskId` 混成一个 ID

一个负责协调（`RequestRecord`），一个负责工作目标（`TaskRecord`），不是同一层。

### 3. 把 runtime slot 当成 durable task

运行时执行单元（`RuntimeTaskState`，存在 `ConcurrentHashMap` 中）会结束，但 durable task（`TaskRecord`，持久化到 `.tasks/` 目录）还可能继续存在。

### 4. 把 worktree 当成任务本身

worktree（`WorktreeRecord`）只是执行目录边界，不是任务目标。

### 5. 只会讲"系统能并行"，却说不清每层对象各自负责什么

这是最常见也最危险的模糊表达。

真正清楚的教学，不是说"这里好多 virtual thread 很厉害"，而是能把下面这句话讲稳：

> 队友负责长期协作，请求负责协调流程，任务负责表达目标，运行时槽位负责承载执行，worktree 负责隔离执行目录。

## 五层的 Java 存储对比

| 层 | Java 类型 | 存储方式 | 生命周期 |
|---|---|---|---|
| Teammate | `TeamMember` record | `.team/config.json`（Jackson 序列化） | 跨 JVM 重启持久存在 |
| Protocol Request | `RequestRecord` record | `RequestTracker` 的 `ConcurrentHashMap` | 仅在 JVM 运行时存在 |
| Task | `TaskRecord` record | `.tasks/` 目录下的 JSON 文件 | 跨 JVM 重启持久存在 |
| Runtime Task | `RuntimeTaskState` record | `RuntimeTaskManager` 的 `ConcurrentHashMap` | 仅在 JVM 运行时存在 |
| Worktree | `WorktreeRecord` record | `.worktrees/index.json` + git worktree | 文件系统级别持久存在 |

注意：
- `ConcurrentHashMap` 中的数据在 JVM 退出后丢失——这就是"运行时状态"。
- `.tasks/` 目录下的 JSON 文件跨 JVM 重启——这就是"持久化状态"。
- 这个区别和原版 Python 实现的设计意图完全一致，只是 Java 用 `ConcurrentHashMap` 代替了 Python dict，用 Jackson 代替了 `json.dump()`。

## 读完这篇你应该能自己说清楚

至少能完整说出下面这两句话：

1. `s17` 的自治认领，认领的是 `s12` 的工作图任务（`TaskRecord`），不是 `s13` 的运行时槽位（`RuntimeTaskState`）。
2. `s18` 的 worktree（`WorktreeRecord`），绑定的是任务的执行车道，而不是把任务本身变成目录。

如果这两句你已经能稳定说清，`s15-s18` 这一大段主线就基本不会再拧巴了。
