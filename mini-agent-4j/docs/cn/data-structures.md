# Core Data Structures (核心数据结构总表)

> 学习 agent，最容易迷路的地方不是功能太多，而是不知道"状态到底放在哪"。这份文档把主线章节和桥接章节里反复出现的关键数据结构集中列出来，方便你把整套系统看成一张图。

## 推荐联读

建议把这份总表当成"状态地图"来用：

- 先不懂词，就回 [`glossary.md`](./glossary.md)。
- 先不懂边界，就回 [`entity-map.md`](./entity-map.md)。
- 如果卡在 `TaskRecord` 和 `RuntimeTaskState`，继续看 [`s13a-runtime-task-model.md`](./s13a-runtime-task-model.md)。
- 如果卡在 MCP 为什么还有 resource / prompt / elicitation，继续看 [`s19a-mcp-capability-layers.md`](./s19a-mcp-capability-layers.md)。

## 先记住两个总原则

### 原则 1：区分"内容状态"和"流程状态"

- `messages`、`toolResult`、memory 正文，属于内容状态。
- `turnCount`、`transition`、`pendingClassifierCheck`，属于流程状态。

很多初学者会把这两类状态混在一起。
一混，后面就很难看懂为什么一个结构完整的系统会需要控制平面。

### 原则 2：区分"持久状态"和"运行时状态"

- task、memory、schedule 这类状态，通常会落盘，跨会话存在。
- runtime task、当前 permission decision、当前 MCP connection 这类状态，通常只在系统运行时活着。

## 1. 查询与对话控制状态

### Message

作用：保存当前对话和工具往返历史。

最小形状（Java record）：

```java
/**
 * 对话中的一条消息。
 * content 在简单场景下是纯文本；
 * 支持工具调用后，content 会变成包含 text / tool_use / tool_result 的块列表。
 */
public sealed interface Message permits UserMessage, AssistantMessage {
    String role();   // "user" | "assistant"
    Object content(); // String 或 List<ContentBlock>
}
```

当 `content` 为块列表时，使用 sealed 接口建模：

```java
public sealed interface ContentBlock
    permits TextBlock, ToolUseBlock, ToolResultBlock {
    String type(); // "text" | "tool_use" | "tool_result"
}

public record TextBlock(String text) implements ContentBlock {
    public String type() { return "text"; }
}

public record ToolUseBlock(String toolUseId, String name, Map<String, Object> input)
    implements ContentBlock {
    public String type() { return "tool_use"; }
}

public record ToolResultBlock(String toolUseId, String content, boolean isError)
    implements ContentBlock {
    public String type() { return "tool_result"; }
}
```

相关章节：

- `s01`
- `s02`
- `s06`
- `s10`

### NormalizedMessage

作用：把不同来源的消息整理成统一、稳定、可送给模型 API 的消息格式。

最小形状：

```java
/**
 * 标准化后的消息——准备发给模型之前的统一输入格式。
 */
public record NormalizedMessage(
    String role,                      // "user" | "assistant"
    List<NormalizedContentBlock> content
) {}

public record NormalizedContentBlock(
    String type,  // "text" | "tool_use" | "tool_result"
    String text,
    String toolUseId,
    String name,
    Map<String, Object> input,
    String content,
    boolean isError
) {}
```

它和普通 `Message` 的区别是：

- `Message` 偏"系统内部记录"
- `NormalizedMessage` 偏"准备发给模型之前的统一输入"

相关章节：

- `s10`
- [`s10a-message-prompt-pipeline.md`](./s10a-message-prompt-pipeline.md)

### CompactSummary

作用：上下文太长时，用摘要替代旧消息原文。

最小形状：

```java
public record CompactSummary(
    String taskOverview,
    String currentState,
    List<String> keyDecisions,
    List<String> nextSteps
) {}
```

相关章节：

- `s06`
- `s11`

### SystemPromptBlock

作用：把 system prompt 从一整段大字符串，拆成若干可管理片段。

最小形状：

```java
public record SystemPromptBlock(
    String text,
    Optional<String> cacheScope   // 缓存作用域，可能为空
) {}
```

你可以把它理解成：

- `text`：这一段提示词正文
- `cacheScope`：这一段是否可以复用缓存

相关章节：

- `s10`
- [`s10a-message-prompt-pipeline.md`](./s10a-message-prompt-pipeline.md)

### PromptParts

作用：在真正拼成 system prompt 之前，先把各部分拆开管理。

最小形状：

```java
public record PromptParts(
    String core,
    String tools,
    String skills,
    String memory,
    String agentMd,      // 对应原 claude_md，Java 版使用 AGENT.md
    String dynamic
) {}
```

相关章节：

- `s10`

### QueryParams

作用：进入查询主循环时，外部一次性传进来的输入集合。

最小形状：

```java
public record QueryParams(
    List<Message> messages,
    String systemPrompt,
    Map<String, Object> userContext,
    Map<String, Object> systemContext,
    ToolUseContext toolUseContext,
    Optional<String> fallbackModel,
    OptionalInt maxOutputTokensOverride,
    OptionalInt maxTurns
) {}
```

它的重要点在于：

- 这是"本次 query 的入口输入"
- 它和循环内部不断变化的状态，不是同一层

相关章节：

- [`s00a-query-control-plane.md`](./s00a-query-control-plane.md)

### QueryState

作用：保存一条 query 在多轮循环之间不断变化的流程状态。

最小形状：

```java
public record QueryState(
    List<Message> messages,
    ToolUseContext toolUseContext,
    int turnCount,
    int maxOutputTokensRecoveryCount,
    boolean hasAttemptedReactiveCompact,
    OptionalInt maxOutputTokensOverride,
    Optional<String> pendingToolUseSummary,
    boolean stopHookActive,
    Optional<TransitionReason> transition
) {}
```

这类字段的共同特点是：

- 它们不是对话内容
- 它们是"这一轮该怎么继续"的控制状态

相关章节：

- [`s00a-query-control-plane.md`](./s00a-query-control-plane.md)
- `s11`

### TransitionReason

作用：记录"上一轮为什么继续了，而不是结束"。

最小形状（sealed interface + 枚举）：

```java
/**
 * transition reason：记录 query 循环为什么继续而非结束。
 */
public sealed interface TransitionReason {

    record NextTurn() implements TransitionReason {}
    record ReactiveCompactRetry() implements TransitionReason {}
    record TokenBudgetContinuation() implements TransitionReason {}
    record MaxOutputTokensRecovery() implements TransitionReason {}
    record StopHookContinuation() implements TransitionReason {}
}
```

在 Java 里使用 sealed interface 而不是 String 枚举，可以：
- 在 switch 中做穷举模式匹配
- 编译器帮你检查是否覆盖所有分支

常见类型：

- `NextTurn`
- `ReactiveCompactRetry`
- `TokenBudgetContinuation`
- `MaxOutputTokensRecovery`
- `StopHookContinuation`

它的价值不是炫技，而是让：

- 日志更清楚
- 测试更清楚
- 恢复链路更清楚

相关章节：

- [`s00a-query-control-plane.md`](./s00a-query-control-plane.md)
- `s11`

## 2. 工具、权限与 hook 执行状态

### ToolSpec

作用：告诉模型"有哪些工具、每个工具要什么输入"。

最小形状：

```java
public record ToolSpec(
    String name,
    String description,
    Map<String, Object> inputSchema   // JSON Schema 格式
) {}
```

相关章节：

- `s02`
- `s19`

### ToolDispatchMap

作用：把工具名映射到真实执行函数。

最小形状：

```java
/**
 * 工具名 -> 处理函数的映射表。
 * 使用函数式接口 ToolHandler 表示每个工具的执行逻辑。
 */
@FunctionalInterface
public interface ToolHandler {
    ToolResult apply(Map<String, Object> input, ToolUseContext context);
}

// 注册表
Map<String, ToolHandler> dispatchMap = new HashMap<>();
dispatchMap.put("read_file",  this::handleReadFile);
dispatchMap.put("write_file", this::handleWriteFile);
dispatchMap.put("bash",       this::handleBash);
```

相关章节：

- `s02`

### ToolUseContext

作用：把工具运行时需要的共享环境打成一个总线。

最小形状：

```java
public record ToolUseContext(
    Map<String, ToolHandler> tools,
    PermissionContext permissionContext,
    List<McpClientConnection> mcpClients,
    List<Message> messages,
    AppState appState,
    Path cwd,
    ReadFileState readFileState,
    List<Notification> notifications
) {}
```

这层很关键。
因为在更完整的工具执行环境里，工具拿到的不只是 `toolInput`，还包括：

- 当前权限环境
- 当前消息
- 当前 app state
- 当前 MCP client
- 当前文件读取缓存

相关章节：

- [`s02a-tool-control-plane.md`](./s02a-tool-control-plane.md)
- `s07`
- `s19`

### PermissionRule

作用：描述某类工具调用命中后该怎么处理。

最小形状：

```java
public record PermissionRule(
    String toolName,
    String ruleContent,
    PermissionBehavior behavior
) {}

public enum PermissionBehavior {
    ALLOW, DENY, ASK
}
```

相关章节：

- `s07`

### PermissionRuleSource

作用：标记一条权限规则是从哪里来的。

最小形状：

```java
/**
 * 标记权限规则的来源——你不仅知道"有什么规则"，还知道"谁加进来的"。
 */
public enum PermissionRuleSource {
    USER_SETTINGS,
    PROJECT_SETTINGS,
    LOCAL_SETTINGS,
    FLAG_SETTINGS,
    POLICY_SETTINGS,
    CLI_ARG,
    COMMAND,
    SESSION
}
```

这个结构的意义是：

- 你不只知道"有什么规则"
- 还知道"这条规则是谁加进来的"

相关章节：

- `s07`

### PermissionDecision

作用：表示一次工具调用当前该允许、拒绝还是提问。

最小形状：

```java
public record PermissionDecision(
    PermissionBehavior behavior,
    String reason
) {}
```

在更完整的权限流里，`ASK` 结果还可能带：

- 修改后的输入
- 建议写回哪些规则更新
- 一个后台自动分类检查

相关章节：

- `s07`

### PermissionUpdate

作用：描述"这次权限确认之后，要把什么改回配置里"。

最小形状：

```java
public record PermissionUpdate(
    PermissionUpdateType type,
    PermissionRuleSource destination,
    List<PermissionRule> rules
) {}

public enum PermissionUpdateType {
    ADD_RULES, REMOVE_RULES, SET_MODE, ADD_DIRECTORIES
}
```

它解决的是一个很容易被漏掉的问题：

用户这次点了"允许"，到底只是这一次放行，还是要写回会话、项目，甚至用户级配置。

相关章节：

- `s07`

### HookContext

作用：把某个 hook 事件发生时的上下文打包给外部脚本。

最小形状：

```java
public record HookContext(
    HookEvent event,                   // "PreToolUse" / "PostToolUse" / ...
    String toolName,
    Map<String, Object> toolInput,
    Optional<String> toolResult
) {}

public enum HookEvent {
    PRE_TOOL_USE, POST_TOOL_USE, NOTIFICATION, STOP
}
```

相关章节：

- `s08`

### RecoveryState

作用：记录恢复流程已经尝试到哪里。

最小形状：

```java
public record RecoveryState(
    int continuationAttempts,
    int compactAttempts,
    int transportAttempts
) {}
```

相关章节：

- `s11`

## 3. 持久化工作状态

### TodoItem

作用：当前会话里的轻量计划项。

最小形状：

```java
public record TodoItem(
    String content,
    TodoStatus status
) {}

public enum TodoStatus {
    PENDING, IN_PROGRESS, COMPLETED
}
```

相关章节：

- `s03`

### MemoryEntry

作用：保存跨会话仍然有价值的信息。

最小形状：

```java
public record MemoryEntry(
    String name,
    String description,
    MemoryType type,
    MemoryScope scope,
    String body
) {}

public enum MemoryType {
    USER, FEEDBACK, PROJECT, REFERENCE
}

public enum MemoryScope {
    PRIVATE, TEAM
}
```

这里最重要的不是字段多，而是边界清楚：

- 只存不容易从当前项目状态重新推出来的东西
- 记忆可能会过时，要验证

相关章节：

- `s09`

### TaskRecord

作用：磁盘上的工作图任务节点。

最小形状：

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

重点字段：

- `blockedBy`：谁挡着我
- `blocks`：我挡着谁
- `owner`：谁认领了
- `worktree`：在哪个隔离目录里做

相关章节：

- `s12`
- `s17`
- `s18`
- [`s13a-runtime-task-model.md`](./s13a-runtime-task-model.md)

### ScheduleRecord

作用：记录未来要触发的调度任务。

最小形状：

```java
public record ScheduleRecord(
    String id,
    String cron,                       // 标准 5 字段 cron 表达式
    String prompt,
    boolean recurring,
    boolean durable,
    Instant createdAt,
    Optional<Instant> lastFiredAt
) {}
```

相关章节：

- `s14`

## 4. 运行时执行状态

### RuntimeTaskState

作用：表示系统里一个"正在运行的执行单元"。

最小形状：

```java
public record RuntimeTaskState(
    String id,
    RuntimeTaskType type,
    RuntimeTaskStatus status,
    String description,
    Instant startTime,
    Optional<Instant> endTime,
    Path outputFile,
    boolean notified
) {}

public enum RuntimeTaskType {
    LOCAL_BASH, IN_PROCESS_TEAMMATE, MONITOR
}

public enum RuntimeTaskStatus {
    PENDING, RUNNING, COMPLETED, FAILED
}
```

这和 `TaskRecord` 不是一回事：

- `TaskRecord` 管工作目标
- `RuntimeTaskState` 管当前执行槽位

相关章节：

- `s13`
- [`s13a-runtime-task-model.md`](./s13a-runtime-task-model.md)

### TeamMember

作用：记录一个持久队友是谁、在做什么。

最小形状：

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

相关章节：

- `s15`
- `s17`

### MessageEnvelope

作用：队友之间传递结构化消息。

最小形状：

```java
public record MessageEnvelope(
    EnvelopeType type,
    String from,
    String to,
    String requestId,
    String content,
    Map<String, Object> payload,
    Instant timestamp
) {}

public enum EnvelopeType {
    MESSAGE, SHUTDOWN_REQUEST, PLAN_APPROVAL
}
```

相关章节：

- `s15`
- `s16`

### RequestRecord

作用：追踪一个协议请求当前走到哪里。

最小形状：

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

相关章节：

- `s16`

### WorktreeRecord

作用：记录一个任务绑定的隔离工作目录。

最小形状：

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

相关章节：

- `s18`

### WorktreeEvent

作用：记录 worktree 生命周期事件，便于恢复和排查。

最小形状：

```java
public record WorktreeEvent(
    String event,                      // "worktree.create.after" 等
    int taskId,
    String worktree,
    Instant timestamp
) {}
```

相关章节：

- `s18`

## 5. 外部平台与 MCP 状态

### ScopedMcpServerConfig

作用：描述一个 MCP server 应该如何连接，以及它的配置来自哪个作用域。

最小形状：

```java
public record ScopedMcpServerConfig(
    String name,
    McpTransportType type,
    String command,
    List<String> args,
    McpConfigScope scope
) {}

public enum McpTransportType {
    STDIO, SSE, STREAMABLE_HTTP
}

public enum McpConfigScope {
    LOCAL, USER, PROJECT, DYNAMIC, PLUGIN, MANAGED
}
```

这个 `scope` 很重要，因为 server 配置可能来自：

- 本地
- 用户
- 项目
- 动态注入
- 插件或托管来源

相关章节：

- `s19`
- [`s02a-tool-control-plane.md`](./s02a-tool-control-plane.md)
- [`s19a-mcp-capability-layers.md`](./s19a-mcp-capability-layers.md)

### MCPServerConnectionState

作用：表示一个 MCP server 当前连到了哪一步。

最小形状：

```java
public record MCPServerConnectionState(
    String name,
    McpConnectionStatus type,
    ScopedMcpServerConfig config
) {}

public enum McpConnectionStatus {
    CONNECTED, PENDING, FAILED, NEEDS_AUTH, DISABLED
}
```

这层特别重要，因为"有没有接上"不是布尔值，而是多种状态：

- `CONNECTED`
- `PENDING`
- `FAILED`
- `NEEDS_AUTH`
- `DISABLED`

相关章节：

- `s19`
- [`s19a-mcp-capability-layers.md`](./s19a-mcp-capability-layers.md)

### MCPToolSpec

作用：把外部 MCP 工具转换成 agent 内部统一工具定义。

最小形状：

```java
public record MCPToolSpec(
    String name,                       // 例如 "mcp__postgres__query"
    String description,
    Map<String, Object> inputSchema
) {}
```

相关章节：

- `s19`

### ElicitationRequest

作用：表示 MCP server 反过来向用户请求额外输入。

最小形状：

```java
public record ElicitationRequest(
    String serverName,
    String message,
    Map<String, Object> requestedSchema
) {}
```

它提醒你一件事：

- MCP 不只是"模型主动调工具"
- 外部 server 也可能反过来请求补充输入

相关章节：

- [`s19a-mcp-capability-layers.md`](./s19a-mcp-capability-layers.md)

## 最后用一句话把它们串起来

如果你只想记一条总线索，可以记这个：

```text
messages / prompt / query state
  管本轮输入和继续理由

tools / permissions / hooks
  管动作怎么安全执行

memory / task / schedule
  管跨轮、跨会话的持久工作

runtime task / team / worktree
  管当前执行车道

mcp
  管系统怎样向外接能力
```

这份总表最好配合 [`s00-architecture-overview.md`](./s00-architecture-overview.md) 和 [`entity-map.md`](./entity-map.md) 一起看。

## 教学边界

这份总表只负责做两件事：

- 帮你确认一个状态到底属于哪一层
- 帮你确认这个状态大概长什么样

它不负责穷举真实系统里的每一个字段、每一条兼容分支、每一种产品化补丁。

如果你已经知道某个状态归谁管、什么时候创建、什么时候销毁，再回到对应章节看执行路径，理解会顺很多。

## Java 项目中的对应关系

在 mini-agent-4j 项目里，这些数据结构大致分布在以下包和文件中：

| 层级 | 典型包路径 | 典型文件 |
|---|---|---|
| 查询与对话 | `com.example.agent.query` | `QueryParams.java`, `QueryState.java` |
| 工具与权限 | `com.example.agent.tool` | `ToolSpec.java`, `ToolDispatchMap.java` |
| 持久化状态 | `com.example.agent.state` | `TaskRecord.java`, `MemoryEntry.java` |
| 运行时执行 | `com.example.agent.runtime` | `RuntimeTaskState.java`, `TeamMember.java` |
| MCP 平台 | `com.example.agent.mcp` | `ScopedMcpServerConfig.java`, `MCPToolSpec.java` |

教学章节文件（`sessions/S*.java`）和这些数据结构之间，通过 package import 关联。
每章主文件通过 import 引用它需要的数据结构，读者可以按章节顺序阅读代码，也可以按这张总表追踪状态。
