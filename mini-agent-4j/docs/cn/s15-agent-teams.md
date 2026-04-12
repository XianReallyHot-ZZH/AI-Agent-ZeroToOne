# s15: Agent Teams (智能体团队)

`s00 > s01 > s02 > s03 > s04 > s05 > s06 > s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > [ s15 ] > s16 > s17 > s18 > s19`

> *子 agent 适合一次性委派；团队系统解决的是"有人长期在线、能继续接活、能互相协作"。*

## 这一章要解决什么问题

`s04` 的 subagent 已经能帮主 agent 拆小任务。

但 subagent 有一个很明显的边界：

```text
创建 -> 执行 -> 返回摘要 -> 消失
```

这很适合一次性的小委派。
可如果你想做这些事，就不够用了：

- 让一个测试 agent 长期待命
- 让两个 agent 长期分工
- 让某个 agent 未来收到新任务后继续工作

也就是说，系统现在缺的不是"再开一个模型调用"，而是：

**一批有身份、能长期存在、能反复协作的队友。**

## 建议联读

- 如果你还在把 teammate 和 `s04` 的 subagent 混成一类，先回 [`entity-map.md`](./entity-map.md)。
- 如果你准备继续读 `s16-s18`，建议把 [`team-task-lane-model.md`](./team-task-lane-model.md) 放在手边，它会把 teammate、protocol request、task、runtime slot、worktree lane 这五层一起拆开。
- 如果你开始怀疑"长期队友"和"活着的执行槽位"到底是什么关系，配合看 [`s13a-runtime-task-model.md`](./s13a-runtime-task-model.md)。

## 先把几个词讲明白

### 什么是队友

这里的 `teammate` 指的是：

> 一个拥有名字、角色、消息入口和生命周期的持久 agent。

### 什么是名册

名册就是团队成员列表。

它回答的是：

- 现在队伍里有谁
- 每个人是什么角色
- 每个人现在是空闲、工作中还是已关闭

### 什么是邮箱

邮箱就是每个队友的收件箱。

别人把消息发给它，
它在自己的下一轮工作前先去收消息。

## 最小心智模型

这一章最简单的理解方式，是把每个队友都想成：

> 一个有自己循环、自己收件箱、自己上下文的人。

```text
lead
  |
  +-- spawn alice (coder)
  +-- spawn bob (tester)
  |
  +-- send message --> alice inbox
  +-- send message --> bob inbox

alice
  |
  +-- 自己的 messages
  +-- 自己的 inbox
  +-- 自己的 agent loop

bob
  |
  +-- 自己的 messages
  +-- 自己的 inbox
  +-- 自己的 agent loop
```

和 `s04` 的最大区别是：

**subagent 是一次性执行单元，teammate 是长期存在的协作成员。**

## 关键数据结构

### 1. TeamMember

```java
// 教学版用 Map 表示，核心字段只有 3 个
Map<String, Object> member = Map.of(
    "name", "alice",
    "role", "coder",
    "status", "working"
);
```

教学版先只保留这 3 个字段就够了：

- `name`：名字
- `role`：角色
- `status`：状态

### 2. TeamConfig

```java
// 持久化到 .team/config.json
Map<String, Object> config = Map.of(
    "team_name", "default",
    "members", List.of(member1, member2)
);
```

这份名册让系统重启以后，仍然知道：

- 团队里曾经有谁
- 每个人当前是什么角色

### 3. MessageEnvelope

```java
// 每条消息以 JSONL 行追加到 .team/inbox/{name}.jsonl
Map<String, Object> message = Map.of(
    "type", "message",
    "from", "lead",
    "content", "Please review auth module.",
    "timestamp", System.currentTimeMillis() / 1000.0
);
```

`envelope` 这个词本来是"信封"的意思。
程序里用它表示：

> 把消息正文和元信息一起包起来的一条记录。

## 最小实现

### 第一步：先有一份队伍名册

```java
static class TeammateManager {
    private final Path teamDir;
    private final Path configPath;  // .team/config.json
    private Map<String, Object> config;

    TeammateManager(Path teamDir, MessageBus bus) {
        this.teamDir = teamDir;
        this.configPath = teamDir.resolve("config.json");
        this.config = loadConfig();
    }
}
```

名册是本章的起点。
没有名册，就没有真正的"团队实体"。

### 第二步：spawn 一个持久队友

```java
synchronized String spawn(String name, String role, String prompt) {
    // 更新或创建成员记录
    member.put("status", "working");
    saveConfig();

    // 启动虚拟线程运行队友工作循环
    Thread.ofVirtual().name("agent-" + name)
            .start(() -> teammateLoop(name, role, prompt));

    return "Spawned '" + name + "' (role: " + role + ")";
}
```

这里的关键不在于虚拟线程本身，而在于：

**队友一旦被创建，就不只是一次性工具调用，而是一个有持续生命周期的成员。**

Java 21 的虚拟线程（`Thread.ofVirtual()`）非常轻量，可以创建数千个而不会耗尽系统资源，非常适合每个队友跑一个独立循环。

### 第三步：给每个队友一个邮箱

教学版使用 JSONL 文件作为收件箱：

```text
.team/inbox/alice.jsonl
.team/inbox/bob.jsonl
.team/inbox/lead.jsonl
```

发消息时追加一行：

```java
// MessageBus.send() —— 追加一行 JSON 到收件箱
String send(String sender, String to, String content,
            String msgType, Map<String, Object> extra) {
    var msg = new LinkedHashMap<String, Object>();
    msg.put("type", msgType);
    msg.put("from", sender);
    msg.put("content", content);
    msg.put("timestamp", System.currentTimeMillis() / 1000.0);

    ReentrantLock lock = locks.computeIfAbsent(to, k -> new ReentrantLock());
    lock.lock();
    try {
        Path inboxPath = inboxDir.resolve(to + ".jsonl");
        String line = MAPPER.writeValueAsString(msg) + "\n";
        Files.writeString(inboxPath, line,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return "Sent " + msgType + " to " + to;
    } finally {
        lock.unlock();
    }
}
```

收消息时（read-and-drain 语义）：

1. 读出全部 JSONL 行
2. 解析为消息列表
3. 清空收件箱文件

每个收件箱用独立的 `ReentrantLock` 保护，通过 `ConcurrentHashMap` 管理锁池，保证并发安全。

### 第四步：队友每轮先看邮箱，再继续工作

```java
private void teammateLoop(String name, String role, String prompt) {
    var params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .system("You are '" + name + "', role: " + role);

    params.addUserMessage(prompt);  // 注入初始工作指令

    for (int round = 0; round < MAX_WORK_ROUNDS; round++) {
        // 每轮开始前读取收件箱
        List<Map<String, Object>> inbox = bus.readInbox(name);
        for (var msg : inbox) {
            params.addUserMessage(MAPPER.writeValueAsString(msg));
        }

        // 调用 LLM，执行工具...
        Message resp = client.messages().create(params.build());
        // ...（工具执行和结果追加省略）
    }

    setMemberStatus(name, "idle");  // 工作完成后设为 idle
}
```

这一步一定要讲透。

因为它说明：

**队友不是靠"被重新创建"来获得新任务，而是靠"下一轮先检查邮箱"来接收新工作。**

## 如何接到前面章节的系统里

这章最容易出现的误解是：

> 好像系统突然"多了几个人"，但不知道这些人到底接在之前哪一层。

更准确的接法应该是：

```text
用户目标 / lead 判断需要长期分工
  ->
spawn teammate（Thread.ofVirtual()）
  ->
写入 .team/config.json
  ->
通过 inbox 分派消息、摘要、任务线索
  ->
teammate 先 drain inbox
  ->
进入自己的 agent loop 和工具调用
  ->
把结果回送给 lead，或继续等待下一轮工作
```

这里要特别看清三件事：

1. `s12-s14` 已经给了你任务板、后台执行、时间触发这些"工作层"。
2. `s15` 现在补的是"长期执行者"，也就是谁长期在线、谁能反复接活。
3. 本章还没有进入"自己找活"或"自动认领"。

也就是说，`s15` 的默认工作方式仍然是：

- 由 lead 手动创建队友
- 由 lead 通过邮箱分派事情
- 队友在自己的虚拟线程循环里持续处理

真正的自治认领，要到 `s17` 才展开。

## Teammate、Subagent、Runtime Task 到底怎么区分

这是这一组章节里最容易混的点。

可以直接记这张表：

| 机制 | 更像什么 | 生命周期 | 关键边界 |
|---|---|---|---|
| subagent | 一次性外包助手 | 干完就结束 | 重点是"隔离一小段探索性上下文" |
| runtime task | 正在运行的后台执行槽位 | 任务跑完或取消就结束 | 重点是"慢任务稍后回来"，不是长期身份 |
| teammate | 长期在线队友 | 可以反复接任务 | 重点是"有名字、有邮箱、有独立虚拟线程循环" |

再换成更口语的话说：

- subagent 适合"帮我查一下再回来汇报"
- runtime task 适合"这件事你后台慢慢跑，结果稍后通知我"
- teammate 适合"你以后长期负责测试方向"

## 这一章的教学边界

本章先只把 3 件事讲稳：

- 名册
- 邮箱
- 独立循环

这已经足够把"长期队友"这个实体立起来。

但它还没有展开后面两层能力：

### 第一层：结构化协议

也就是：

- 哪些消息只是普通交流
- 哪些消息是带 `request_id` 的结构化请求

这部分放到下一章 `s16`。

### 第二层：自治认领

也就是：

- 队友空闲时能不能自己找活
- 能不能自己恢复工作

这部分放到 `s17`。

## 初学者最容易犯的错

### 1. 把队友当成"名字不同的 subagent"

如果生命周期还是"执行完就销毁"，那本质上还不是 teammate。

### 2. 队友之间共用同一份 messages

这样上下文会互相污染。

每个队友都应该有自己的对话状态（`MessageCreateParams.Builder`）。

### 3. 没有持久名册

如果系统关掉以后完全不知道"团队里曾经有谁"，那就很难继续做长期协作。

### 4. 没有邮箱，靠共享变量直接喊话

教学上不建议一开始就这么做。

因为它会把"队友通信"和"进程内部细节"绑得太死。

Java 版本使用 `ReentrantLock` + JSONL 文件实现邮箱，既保证了并发安全，又保持了文件级消息传递的简洁。

## 学完这一章，你应该真正掌握什么

学完以后，你应该能独立说清下面几件事：

1. teammate 的核心不是"多一个模型调用"，而是"多一个长期存在的执行者"。
2. 团队系统至少需要"名册 + 邮箱 + 独立循环"。
3. 每个队友都应该有自己的 `messages` 和自己的 inbox。
4. subagent 和 teammate 的根本区别在生命周期，而不是名字。

如果这 4 点已经稳了，说明你已经真正理解了"多 agent 团队"是怎么从单 agent 演化出来的。

## 下一章学什么

这一章解决的是：

> 团队成员如何长期存在、互相发消息。

下一章 `s16` 要解决的是：

> 当消息不再只是自由聊天，而要变成可追踪、可批准、可拒绝的协作流程时，该怎么设计。

也就是从"有团队"继续走向"团队协议"。
