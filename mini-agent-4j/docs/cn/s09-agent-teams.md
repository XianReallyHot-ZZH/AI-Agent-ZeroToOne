# s09：多 Agent 团队

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > [ s09 ] s10 > s11 > s12`

> *"Teammate 是有名字的、有记忆的、可以随时对话的长期合作者。"* —— 从临时子 Agent 到持久队友。
>
> **装置层**：MessageBus + TeamManager —— 通过文件收件箱通信的持久 Agent。

## 问题

s04 的子 Agent 是一次性的：生成、执行、返回摘要、销毁。你不能发后续消息，不能问"进展如何？"。你需要有名字、有记忆、能异步通信的持久 Agent。

## 方案

```
Lead Agent（REPL）
  |
  +--- spawn_teammate("alice", "backend", "You are a Java backend engineer...")
  |         |
  |         v
  |    [alice 的 Virtual Thread + 自己的 agent loop + 自己的 messages]
  |
  +--- spawn_teammate("bob", "frontend", "You are a React developer...")
  |         |
  |         v
  |    [bob 的 Virtual Thread + 自己的 agent loop + 自己的 messages]
  |
  +--- MessageBus（.team/inbox/）
        |
        +-- alice.jsonl  （追加写入的收件箱）
        +-- bob.jsonl    （追加写入的收件箱）
        +-- lead.jsonl   （Lead 的收件箱，用于接收回复）
```

每个 Teammate 在自己的 Virtual Thread 中运行，拥有独立的 agent loop。通信通过基于 JSONL 文件的收件箱完成。

## 原理

1. **MessageBus 实现基于文件的跨 Agent 通信。** 每个 Agent 拥有一个收件箱文件：

```java
MessageBus bus = new MessageBus(teamDir.resolve("inbox"));

// 发送消息（追加一行 JSON）
bus.send("lead", "alice", "Please implement the UserService class", "message", null);

// 读取并清空收件箱（读取全部，然后清空）
List<Map<String, Object>> messages = bus.readInbox("alice");

// 广播给所有队友
bus.broadcast("lead", "Team standup: report progress", teamMgr.memberNames());
```

2. **TeamManager 生成持久的 Teammate：

```java
TeamManager teamMgr = new TeamManager(teamDir, bus, client, model, workDir, sandbox);

dispatcher.register("spawn_teammate", input ->
    teamMgr.spawn(
        (String) input.get("name"),   // "alice"
        (String) input.get("role"),   // "backend"
        (String) input.get("prompt")  // 初始指令
    )
);
```

3. **每个 Teammate 在 Virtual Thread 中运行自己的 agent loop：**

```java
// TeamManager.spawn() 内部：
Thread.startVirtualThread(() -> {
    // Teammate 拥有：自己的系统提示词、自己的工具、自己的 messages
    // 工具：bash、read_file、write_file、edit_file、send_message、read_inbox
    // 每次 LLM 调用前：清空自己的收件箱
    teammateLoop(name, systemPrompt, tools, dispatcher);
});
```

4. **Subagent vs Teammate** —— 核心区别：

```
Subagent（s04）：  生成 -> 执行 -> 返回 -> 销毁     （无状态）
Teammate（s09）：  生成 -> 工作 -> 空闲 -> 工作 -> 关闭  （有状态）
```

5. **REPL 命令**用于团队管理：

```
/team   -- 列出所有队友及状态
/inbox  -- 读取 Lead 的收件箱消息
```

## 变更对比

| 组件          | s08                 | s09                               |
|---------------|---------------------|-----------------------------------|
| Agent 模型    | 单 Agent            | Lead + 持久 Teammate              |
| 通信          | （无）              | `MessageBus`（JSONL 文件收件箱）   |
| 新工具        | （无）              | `spawn_teammate`、`list_teammates`、`send_message`、`read_inbox`、`broadcast` |
| 生命周期      | （无）              | `TeamManager.spawn()`、Virtual Thread |
| 新增类        | `BackgroundManager` | `MessageBus`、`TeamManager`、`Teammate`（record） |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S09AgentTeams"
```

1. `生成一个叫 "alice" 的队友，角色是 "backend"，负责 Java 代码`
2. `给 alice 发消息："创建一个 UserService.java 文件"`
3. `/inbox`（检查 alice 是否回复了）
4. `广播给所有队友："汇报目前的工作进展"`
5. `/team`（查看队友状态）
