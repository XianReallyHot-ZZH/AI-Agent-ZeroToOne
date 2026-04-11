# s00: Architecture Overview (架构总览)

> 这一章是全仓库的地图。
> 如果你只想先知道"整个系统到底由哪些模块组成、为什么是这个学习顺序"，先读这一章。

## 先说结论

这套仓库的主线是合理的。

它最重要的优点，不是"章节数量多"，而是它把学习过程拆成了四个阶段：

1. 先做出一个真的能工作的 agent。
2. 再补安全、扩展、记忆和恢复。
3. 再把临时清单升级成持久化任务系统。
4. 最后再进入多 agent、隔离执行和外部工具平台。

这个顺序符合初学者的心智。

因为一个新手最需要的，不是先知道所有高级细节，而是先建立一条稳定的主线：

`用户输入 -> 模型思考 -> 调工具 -> 拿结果 -> 继续思考 -> 完成`

只要这条主线还没真正理解，后面的权限、hook、memory、MCP 都会变成一堆零散名词。

## 这套仓库到底要还原什么

本仓库的目标不是逐行复制任何一个生产仓库。

本仓库真正要还原的是：

- 主要模块有哪些
- 模块之间怎么协作
- 每个模块的核心职责是什么
- 关键状态存在哪里
- 一条请求在系统里是怎么流动的

也就是说，我们追求的是：

**设计主脉络高保真，而不是所有外围实现细节 1:1。**

这很重要。

如果你是为了自己从 0 到 1 做一个类似系统，那么你真正需要掌握的是：

- 核心循环
- 工具机制
- 规划与任务
- 上下文管理
- 权限与扩展点
- 持久化
- 多 agent 协作
- 工作隔离
- 外部工具接入

而不是打包、跨平台兼容、历史兼容分支或产品化胶水代码。

## 三条阅读原则

### 1. 先学最小版本，再学结构更完整的版本

比如子 agent。

最小版本只需要：

- 父 agent 发一个子任务
- 子 agent 用自己的 `List<Message>`
- 子 agent 返回一个摘要

这已经能解决 80% 的核心问题：上下文隔离。

等这个最小版本你真的能写出来，再去补更完整的能力，比如：

- 继承父上下文的 fork 模式
- 独立权限
- 虚拟线程后台运行
- worktree 隔离

### 2. 每个新名词都必须先解释

本仓库会经常用到一些词：

- `state machine`
- `dispatch map`
- `dependency graph`
- `frontmatter`
- `worktree`
- `MCP`

如果你对这些词不熟，不要硬扛。
应该立刻去看术语表：[`glossary.md`](./glossary.md)

如果你想先知道"这套仓库到底教什么、不教什么"，建议配合看：

- [`teaching-scope.md`](./teaching-scope.md)

如果你想先把最关键的数据结构建立成整体地图，可以配合看：

- [`data-structures.md`](./data-structures.md)

如果你已经知道章节顺序没问题，但一打开本地 `src/main/java/com/example/agent/sessions/` 就会重新乱掉，建议再配合看：

- [`s00f-code-reading-order.md`](./s00f-code-reading-order.md)

### 3. 不把复杂外围细节伪装成"核心机制"

好的教学，不是把一切都讲进去。

好的教学，是把真正关键的东西讲完整，把不关键但很复杂的东西先拿掉。

所以本仓库会刻意省略一些不属于主干的内容，比如：

- Maven 打包与发布
- 企业策略接线
- 遥测
- 多客户端表层集成
- 历史兼容层

## 建议配套阅读的文档

除了主线章节，我建议把下面两份文档当作全程辅助地图：

| 文档 | 用途 |
|---|---|
| [`teaching-scope.md`](./teaching-scope.md) | 帮你分清哪些内容属于教学主线，哪些只是维护者侧补充 |
| [`data-structures.md`](./data-structures.md) | 帮你集中理解整个系统的关键状态和数据结构 |
| [`s00f-code-reading-order.md`](./s00f-code-reading-order.md) | 帮你把"章节顺序"和"本地代码阅读顺序"对齐，避免重新乱翻源码 |

如果你已经读到中后半程，想把"章节之间缺的那一层"补上，再加看下面这些桥接文档：

| 文档 | 它补的是什么 |
|---|---|
| [`s00d-chapter-order-rationale.md`](./s00d-chapter-order-rationale.md) | 为什么这套课要按现在这个顺序讲，哪些重排会把读者心智讲乱 |
| [`s00e-reference-module-map.md`](./s00e-reference-module-map.md) | 参考仓库里真正重要的模块簇，和当前课程章节是怎样一一对应的 |
| [`s00a-query-control-plane.md`](./s00a-query-control-plane.md) | 为什么一个更完整的系统不能只靠 `List<Message> + while(true)` |
| [`s00b-one-request-lifecycle.md`](./s00b-one-request-lifecycle.md) | 一条请求如何从用户输入一路流过 query、tools、permissions、tasks、teams、MCP 再回到主循环 |
| [`s02a-tool-control-plane.md`](./s02a-tool-control-plane.md) | 为什么工具层不只是 `toolName -> handler` |
| [`s10a-message-prompt-pipeline.md`](./s10a-message-prompt-pipeline.md) | 为什么 system prompt 不是模型完整输入的全部 |
| [`s13a-runtime-task-model.md`](./s13a-runtime-task-model.md) | 为什么任务板里的 task 和正在运行的 task 不是一回事 |
| [`s19a-mcp-capability-layers.md`](./s19a-mcp-capability-layers.md) | 为什么 MCP 正文先讲 tools-first，但平台层还要再补一张地图 |
| [`entity-map.md`](./entity-map.md) | 帮你把 Message、Task、RuntimeTask、Subagent、Teammate、Worktree、McpServer 这些实体彻底分开 |

## 四阶段学习路径

### 阶段 1：核心单 agent (`s01-s06`)

目标：先做出一个能干活的 agent。

| 章节 | Java 文件 | 学什么 | 解决什么问题 |
|---|---|---|---|
| `s01` | `S01AgentLoop.java` | Agent Loop | 没有循环，就没有 agent |
| `s02` | `S02ToolUse.java` | Tool Use | 让模型从"会说"变成"会做" |
| `s03` | `S03TodoWrite.java` | Todo / Planning | 防止大任务乱撞 |
| `s04` | `S04Subagent.java` | Subagent | 防止上下文被大任务污染 |
| `s05` | `S05SkillLoading.java` | Skills | 按需拿知识，不把所有知识塞进提示词 |
| `s06` | `S06ContextCompact.java` | Context Compact | 防止上下文无限膨胀 |

这一阶段结束后，你已经有了一个真正可运行的 coding agent 雏形。

### 阶段 2：生产加固 (`s07-s11`)

目标：让 agent 不只是能跑，而是更安全、更稳、更可扩展。

| 章节 | Java 文件 | 学什么 | 解决什么问题 |
|---|---|---|---|
| `s07` | `S07PermissionSystem.java` | Permission System | 危险操作先过权限关 |
| `s08` | `S08HookSystem.java` | Hook System | 不改主循环也能扩展行为 |
| `s09` | `S09MemorySystem.java` | Memory System | 让真正有价值的信息跨会话存在 |
| `s10` | `S10SystemPrompt.java` | System Prompt | 把系统说明、工具、约束组装成稳定输入 |
| `s11` | `S11ErrorRecovery.java` | Error Recovery | 出错后能恢复，而不是直接崩溃 |

### 阶段 3：任务管理 (`s12-s14`)

目标：把"聊天中的清单"升级成"磁盘上的任务图"。

| 章节 | Java 文件 | 学什么 | 解决什么问题 |
|---|---|---|---|
| `s12` | `S12TaskSystem.java` | Task System | 大任务要有持久结构 |
| `s13` | `S13BackgroundTasks.java` | Background Tasks | 慢操作不应该卡住前台思考 |
| `s14` | `S14CronScheduler.java` | Cron Scheduler | 让系统能在未来自动做事 |

### 阶段 4：多 agent 与外部系统 (`s15-s19`)

目标：从单 agent 升级成真正的平台。

| 章节 | Java 文件 | 学什么 | 解决什么问题 |
|---|---|---|---|
| `s15` | `S15AgentTeams.java` | Agent Teams | 让多个 agent 协作 |
| `s16` | `S16TeamProtocols.java` | Team Protocols | 让协作有统一规则 |
| `s17` | `S17AutonomousAgents.java` | Autonomous Agents | 让 agent 自己找活、认领任务 |
| `s18` | `S18WorktreeIsolation.java` | Worktree Isolation | 并行工作时互不踩目录 |
| `s19` | `S19McpPlugin.java` | MCP & Plugin | 接入外部工具与外部能力 |

## 章节速查表：每章到底新增了哪一层状态

很多读者读到中途会开始觉得：

- 这一章到底是在加工具，还是在加状态
- 这个机制是"输入层"的，还是"执行层"的
- 学完这一章以后，我手里到底多了一个什么东西

所以这里给一张全局速查表。
读每章以前，先看这一行；读完以后，再回来检查自己是不是真的吃透了这一行。

| 章节 | 新增的核心结构 | Java 类型 | 它接在系统哪一层 | 学完你应该会什么 |
|---|---|---|---|---|
| `s01` | `messages` / `LoopState` | `List<Message>` / `enum LoopState` | 主循环 | 手写一个最小 agent 闭环 |
| `s02` | `ToolSpec` / `ToolDispatchMap` | `ToolSpec` record / `Map<String, ToolHandler>` | 工具层 | 把模型意图路由成真实动作 |
| `s03` | `TodoItem` / `PlanState` | `TodoItem` record / `PlanState` record | 过程规划层 | 让 agent 按步骤推进，而不是乱撞 |
| `s04` | `SubagentContext` | `SubagentContext` record | 执行隔离层 | 把探索性工作丢进干净子上下文 |
| `s05` | `SkillRegistry` / `SkillContent` | `SkillRegistry` / `SkillContent` record | 知识注入层 | 只在需要时加载额外知识 |
| `s06` | `CompactSummary` / `PersistedOutput` | `CompactSummary` record / `Path persistedOutput` | 上下文管理层 | 控制上下文大小又不丢主线 |
| `s07` | `PermissionRule` / `PermissionDecision` | `PermissionRule` record / `enum PermissionDecision` | 安全控制层 | 让危险动作先经过决策管道 |
| `s08` | `HookEvent` / `HookResult` | `HookEvent` record / `HookResult` record | 扩展控制层 | 不改主循环也能插入扩展逻辑 |
| `s09` | `MemoryEntry` / `MemoryStore` | `MemoryEntry` record / `MemoryStore` | 持久上下文层 | 只把真正跨会话有价值的信息留下 |
| `s10` | `PromptParts` / `SystemPromptBlock` | `PromptParts` record / `SystemPromptBlock` record | 输入组装层 | 把模型输入拆成可管理的管道 |
| `s11` | `RecoveryState` / `TransitionReason` | `RecoveryState` record / `enum TransitionReason` | 恢复控制层 | 出错后知道为什么继续、怎么继续 |
| `s12` | `TaskRecord` / `TaskStatus` | `TaskRecord` record / `enum TaskStatus` | 工作图层 | 把临时清单升级成持久化任务图 |
| `s13` | `RuntimeTaskState` / `Notification` | `RuntimeTaskState` record / `Notification` record | 运行时执行层 | 让慢任务后台运行、稍后回送结果 |
| `s14` | `ScheduleRecord` / `CronTrigger` | `ScheduleRecord` record / `CronTrigger` record | 定时触发层 | 让时间本身成为工作触发器 |
| `s15` | `TeamMember` / `MessageEnvelope` | `TeamMember` record / `MessageEnvelope` record | 多 agent 基础层 | 让队友长期存在、反复接活 |
| `s16` | `ProtocolEnvelope` / `RequestRecord` | `ProtocolEnvelope` record / `RequestRecord` record | 协作协议层 | 让团队从自由聊天升级成结构化协作 |
| `s17` | `ClaimPolicy` / `AutonomyState` | `ClaimPolicy` record / `AutonomyState` record | 自治调度层 | 让 agent 空闲时自己找活、恢复工作 |
| `s18` | `WorktreeRecord` / `TaskBinding` | `WorktreeRecord` record / `TaskBinding` record | 隔离执行层 | 给并行任务分配独立工作目录 |
| `s19` | `McpServerConfig` / `CapabilityRoute` | `McpServerConfig` record / `CapabilityRoute` record | 外部能力层 | 把外部能力并入系统主控制面 |

## 整个系统的大图

先看最重要的一张图：

```text
User
  |
  v
List<Message>
  |
  v
+-------------------------+
|  Agent Loop (s01)       |
|                         |
|  1. 组装输入            |
|  2. 调模型              |
|  3. 看 stopReason       |
|  4. 如果要调工具就执行   |
|  5. 把结果写回 messages  |
|  6. 继续下一轮           |
+-------------------------+
  |
  +------------------------------+
  |                              |
  v                              v
Tool Pipeline                Context / State
(s02, s07, s08)              (s03, s06, s09, s10, s11)
  |                              |
  v                              v
Tasks / Teams / Worktree / MCP (s12-s19)
```

你可以把它理解成三层：

### 第一层：主循环

这是系统心脏。

它只做一件事：
**不停地推动"思考 -> 行动 -> 观察 -> 再思考"的循环。**

在 `mini-agent-4j` 中，主循环由 `S01AgentLoop.java` 实现，核心结构是一个 `while` 循环配合 Anthropic Java SDK 的 `Message` API：

```java
while (state == LoopState.RUNNING) {
    Message response = client.messages().create(params.build());
    // 处理 response，执行工具，追加结果...
}
```

### 第二层：横切机制

这些机制不是替代主循环，而是"包在主循环周围"：

- 权限
- hooks
- memory
- prompt 组装
- 错误恢复
- 上下文压缩

它们的作用，是让主循环更安全、更稳定、更聪明。

### 第三层：更大的工作平台

这些机制把单 agent 升级成更完整的系统：

- 任务图
- 后台任务
- 多 agent 团队
- worktree 隔离
- MCP 外部工具

## 你真正需要掌握的关键状态

理解 agent，最重要的不是背很多功能名，而是知道**状态放在哪里**。

下面是这个仓库里最关键的几类状态：

### 1. 对话状态：`List<Message>`

这是 agent 当前上下文的主体。

它保存：

- 用户说了什么
- 模型回复了什么
- 调用了哪些工具
- 工具返回了什么

你可以把它想成 agent 的"工作记忆"。

### 2. 工具注册表：`Map<String, ToolHandler>`

这是一张"工具名 -> Java 方法引用"的映射表。

这类结构常被叫做 `dispatch map`。

意思很简单：

- 模型说"我要调用 `read_file`"
- 代码就去 `Map` 里找 `read_file` 对应的 `ToolHandler`
- 找到以后调用其 `handle()` 方法

在 `mini-agent-4j` 中，每个工具都是一个实现了 `ToolHandler` 接口的 Java 类，通过 `Map<String, ToolHandler>` 注册到系统中：

```java
Map<String, ToolHandler> dispatchMap = new HashMap<>();
dispatchMap.put("read_file", new ReadFileHandler());
dispatchMap.put("bash", new BashHandler());
```

### 3. 计划与任务状态：`TodoItem` / `TaskRecord`

这部分保存：

- 当前有哪些事要做
- 哪些已经完成
- 哪些被别的任务阻塞
- 哪些可以并行

### 4. 权限与策略状态

这部分保存：

- 当前权限模式是什么
- 允许规则有哪些
- 拒绝规则有哪些
- 最近是否连续被拒绝

### 5. 持久化状态

这部分保存那些"不该跟着一次对话一起消失"的东西：

- memory 文件
- task 文件
- transcript
- background task 输出
- worktree 绑定信息

在 `mini-agent-4j` 中，持久化状态以 JSON 文件的形式存放在 `.agent/` 目录下：

```text
.agent/
  memory.json
  tasks.json
  schedules.json
  hooks.json
  permissions.json
```

## 如果你想做出结构完整的版本，至少要有哪些数据结构

如果你的目标是自己写一个结构完整、接近真实主脉络的类似系统，最低限度要把下面这些数据结构设计清楚：

```java
/**
 * mini-agent-4j 全局状态容器
 *
 * 这不是一个要求你一开始就全部写完的设计。
 * 它的作用只是告诉你：
 * 一个像样的 agent 系统，不只是 List<Message> + Map<String, ToolHandler>。
 * 它最终会长成一个带很多子模块的状态系统。
 */
public class AgentState {

    // === 核心对话 ===
    List<Message> messages;
    Map<String, ToolHandler> tools;
    List<ToolSpec> toolSchemas;

    // === 规划 ===
    Optional<PlanState> todo;
    Optional<TaskStore> tasks;

    // === 安全与扩展 ===
    Optional<PermissionSystem> permissions;
    Optional<HookSystem> hooks;
    Optional<MemoryStore> memories;
    Optional<PromptBuilder> promptBuilder;

    // === 上下文管理 ===
    CompactState compactState;
    RecoveryState recoveryState;

    // === 后台与调度 ===
    Optional<BackgroundExecutor> background;
    Optional<CronScheduler> cron;

    // === 多 agent ===
    Optional<TeamStore> teammates;
    Optional<WorktreeSession> worktreeSession;
    Map<String, McpClient> mcpClients;
}
```

这不是要求你一开始就把这些全写完。

这张表的作用只是告诉你：

**一个像样的 agent 系统，不只是 `List<Message> + Map<String, ToolHandler>`。**

它最终会长成一个带很多子模块的状态系统。

## 一条请求是怎么流动的

```text
1. 用户发来任务
2. 系统组装 prompt 和上下文
3. 模型返回普通文本，或者返回 tool_use
4. 如果返回 tool_use：
   - 先过 permission
   - 再过 hook
   - 然后执行工具
   - 把 tool_result 写回 List<Message>
5. 主循环继续
6. 如果任务太大：
   - 可能写入 todo / tasks
   - 可能派生 subagent
   - 可能触发 compact
   - 可能走 background / team / worktree / MCP
7. 直到模型结束这一轮
```

在 `mini-agent-4j` 中，这条流由 `S01AgentLoop.java` 中的主循环驱动，每个阶段的机制分别封装在对应的 `S0xXxx.java` 中。所有章节共享同一个 Anthropic Java SDK 客户端，通过 `Maven` 管理依赖。

这条流是全仓库最重要的主脉络。

你在后面所有章节里看到的机制，本质上都只是插在这条流的不同位置。

## Java 21 特性的使用

`mini-agent-4j` 项目基于 Java 21，充分利用了以下特性：

| 特性 | 在项目中的用途 |
|---|---|
| `record` | 所有核心数据结构（`TodoItem`、`TaskRecord`、`ToolSpec` 等）使用 record 定义，自动获得不可变性和 `equals`/`hashCode`/`toString` |
| `sealed interface` / `pattern matching` | 在 `LoopState`、`PermissionDecision`、`TransitionReason` 等枚举或密封接口上使用 `switch` 模式匹配，替代传统的 if-else 链 |
| Virtual Threads (`Thread.ofVirtual()`) | 后台任务执行（`s13`）和多 agent 并行（`s15`）使用虚拟线程，轻量且无需手动管理线程池 |
| Text Blocks (`"""`) | 构建 system prompt 和工具描述时使用文本块，提高可读性 |
| `Optional` | 所有可能为空的子模块状态（`todo`、`tasks`、`permissions` 等）用 `Optional` 包装，避免空指针 |

## 读者最容易混淆的几组概念

### `Todo` 和 `Task` 不是一回事

- `Todo`：轻量、临时、偏会话内，用 `TodoItem` record 表示
- `Task`：持久化、带状态、带依赖关系，用 `TaskRecord` record 表示

### `Memory` 和 `Context` 不是一回事

- `Context`：这一轮工作临时需要的信息
- `Memory`：未来别的会话也可能仍然有价值的信息

### `Subagent` 和 `Teammate` 不是一回事

- `Subagent`：通常是当前 agent 派生出来的一次性帮手
- `Teammate`：更偏向长期存在于团队中的协作角色

### `Prompt` 和 `System Reminder` 不是一回事

- `System Prompt`：较稳定的系统级输入
- `System Reminder`：每轮动态变化的补充上下文

## 项目结构

```text
mini-agent-4j/
  pom.xml                          # Maven 构建配置，管理 Anthropic SDK 等依赖
  src/
    main/
      java/
        com/
          example/
            agent/
              Main.java            # 程序入口
              sessions/            # 所有章节的 Java 实现
                S01AgentLoop.java
                S02ToolUse.java
                S03TodoWrite.java
                S04Subagent.java
                S05SkillLoading.java
                S06ContextCompact.java
                S07PermissionSystem.java
                S08HookSystem.java
                S09MemorySystem.java
                S10SystemPrompt.java
                S11ErrorRecovery.java
                S12TaskSystem.java
                S13BackgroundTasks.java
                S14CronScheduler.java
                S15AgentTeams.java
                S16TeamProtocols.java
                S17AutonomousAgents.java
                S18WorktreeIsolation.java
                S19McpPlugin.java
                SFullAgent.java    # 完整集成版
  docs/
    cn/                            # 中文文档
      s00-architecture-overview.md
      s01-the-agent-loop.md
      s02-tool-use.md
      ...                          # 每章对应一份文档
  skills/                          # 技能模板目录
  .agent/                          # 运行时持久化目录
    memory.json
    tasks.json
    schedules.json
    hooks.json
    permissions.json
```

## 这套仓库刻意省略了什么

为了让初学者能顺着学下去，本仓库不会把下面这些内容塞进主线：

- Maven 打包与发布到中央仓库的流程
- 真实商业产品中的账号、策略、遥测、灰度等逻辑
- 只服务于兼容性和历史负担的复杂分支
- 某些非常复杂但教学收益很低的边角机制

这不是因为这些东西"不存在"。

而是因为对一个从 0 到 1 造类似系统的读者来说，主干先于枝叶。

## 这一章之后怎么读

推荐顺序：

1. 先读 `s01` 和 `s02`
2. 然后读 `s03` 到 `s06`
3. 进入 `s07` 到 `s10`
4. 接着补 `s11`
5. 最后再读 `s12` 到 `s19`

如果你在某一章觉得名词开始打结，回来看这一章和术语表就够了。

---

**一句话记住全仓库：**

先做出能工作的最小循环，再一层一层给它补上规划、隔离、安全、记忆、任务、协作和外部能力。
