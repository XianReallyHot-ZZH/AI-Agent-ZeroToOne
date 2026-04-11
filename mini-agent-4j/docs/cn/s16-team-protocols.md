# s16：团队协议

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > [ s16 ] s17 > s18 > s19`

> *"协议 = 消息类型 + request_id 关联 + 状态机跟踪。"* —— 一种 FSM 模式，两种应用。
>
> **装置层**：握手 —— Lead 与 Teammate 之间的结构化请求-响应。

## 问题

s15 的 `send_message` 是非结构化的。当 Lead 想关闭一个 Teammate 时，发一条消息然后寄希望于对方执行。没有确认，没有追踪请求是被批准还是被拒绝。你需要带关联 ID 的结构化握手机制。

## 方案

```
关闭协议：
  Lead                                     Teammate
    |                                         |
    |--- shutdown_request(req_id: uuid-1) --->|
    |                                         | （决定：批准或拒绝）
    |<-- shutdown_response(req_id: uuid-1) ---|
    |                                         |
    |  （Lead 通过 req_id 匹配响应）           |

计划审批协议：
  Teammate                                 Lead
    |                                         |
    |--- plan_request(req_id: uuid-2) ------->|
    |                                         | （审查计划）
    |<-- plan_approval(req_id: uuid-2) -------|
    |                                         |
    |  （Teammate 通过 req_id 检查响应）       |
```

两种协议共享同一个 FSM：`pending -> approved | rejected`。关联通过匹配 `request_id` 实现。

## 原理

1. **TeamProtocol 在 `ConcurrentHashMap` 中追踪待处理请求：

```java
TeamProtocol protocol = new TeamProtocol(bus);

// 关闭请求 —— Lead 发起
dispatcher.register("shutdown_request", input ->
    protocol.requestShutdown((String) input.get("teammate"))
);
// 返回："Shutdown request sent to alice (req_id: uuid-1234)"

// 检查关闭状态 —— Lead 检查
dispatcher.register("shutdown_response", input ->
    protocol.checkShutdownStatus((String) input.get("request_id"))
);
// 返回："alice: approved" 或 "alice: pending"
```

2. **计划审批** —— Teammate 提交，Lead 审查：

```java
// Lead 审查 Teammate 的计划
dispatcher.register("plan_approval", input ->
    protocol.reviewPlan(
        (String) input.get("request_id"),
        Boolean.TRUE.equals(input.get("approve")),
        (String) input.get("feedback"))
);
// 批准："Plan approved for req_id uuid-5678"
// 拒绝："Plan rejected: needs more test coverage"
```

3. **Request ID 关联。** 每个请求获得一个 UUID。响应携带相同的 UUID，实现异步关联：

```java
// TeamProtocol 内部：
String requestId = UUID.randomUUID().toString();
pendingShutdowns.put(requestId, Map.of(
    "teammate", teammateName,
    "status", "pending"
));
```

4. **消息通过 MessageBus 传递。** 协议使用 s15 相同的 `bus.send()`，但使用结构化的消息类型：

```
{ type: "shutdown_request", request_id: "uuid-1", from: "lead" }
{ type: "shutdown_response", request_id: "uuid-1", approved: true }
{ type: "plan_request", request_id: "uuid-2", plan: "..." }
{ type: "plan_approval", request_id: "uuid-2", approved: false, feedback: "..." }
```

## 变更对比

| 组件          | s15                 | s16                               |
|---------------|---------------------|-----------------------------------|
| 通信          | 非结构化消息        | 结构化协议（shutdown、plan）      |
| 关联机制      | （无）              | `request_id`（UUID）              |
| 状态追踪      | （无）              | `ConcurrentHashMap` 待处理请求    |
| 新工具        | 5 个（团队工具）    | +3：`shutdown_request`、`shutdown_response`、`plan_approval` |
| 新增类        | （无）              | `TeamProtocol`                    |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S16TeamProtocols"
```

1. `生成一个叫 "alice" 的队友，角色是 "backend"`
2. `给 alice 发一个任务，然后请求优雅关闭`
3. `检查关闭请求的状态`
4. `等待 alice 提交计划，然后审查它`
