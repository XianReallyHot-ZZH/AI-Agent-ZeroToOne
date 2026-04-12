# s00e: 参考仓库模块映射图

> 这是一份给维护者和认真学习者用的校准文档。
> 它不是让读者逐行读逆向源码。
>
> 它只回答一个很关键的问题：
>
> **如果把参考仓库里真正重要的模块簇，和当前教学仓库的章节顺序对照起来看，现在这套课程顺序到底合不合理？**

## 先说结论

合理。

当前这套 `s01 -> s19` 的顺序，整体上是对的，而且比"按源码目录顺序讲"更接近真实系统的设计主干。

原因很简单：

- 参考仓库里目录很多
- 但真正决定系统骨架的，是少数几簇控制、状态、任务、团队、隔离执行和外部能力模块
- 这些高信号模块，和当前教学仓库的四阶段主线基本是对齐的

所以正确动作不是把教程改成"跟着源码树走"。

正确动作是：

- 保留现在这条按依赖关系展开的主线
- 把它和参考仓库的映射关系讲明白
- 继续把低价值的产品外围细节挡在主线外

## 本仓库的代码组织方式

本仓库（mini-agent-4j）的代码组织与 Python 原版不同：

- **Python 原版**：每个章节是一个独立的 `agents/sXX_*.py` 文件
- **Java 版本**：每个章节是一个独立的 `com.example.agent.sessions.SXX*.java` 类

所有章节代码都在同一个包下：

```text
src/main/java/com/example/agent/sessions/
  S01AgentLoop.java          -- 主循环
  S02ToolUse.java            -- 工具执行
  S03TodoWrite.java          -- 会话计划
  S04Subagent.java           -- 子任务隔离
  S05SkillLoading.java       -- 按需知识注入
  S06ContextCompact.java     -- 上下文压缩
  S07PermissionSystem.java   -- 权限闸门
  S08HookSystem.java         -- Hook 系统
  S09MemorySystem.java       -- 跨会话记忆
  S10SystemPrompt.java       -- Prompt 装配
  S11ErrorRecovery.java      -- 恢复与续行
  S12TaskSystem.java         -- 持久任务图
  S13BackgroundTasks.java    -- 后台任务
  S14CronScheduler.java      -- 定时触发
  S15AgentTeams.java         -- 持久队友
  S16TeamProtocols.java      -- 团队协议
  S17AutonomousAgents.java   -- 自治 agent
  S18WorktreeIsolation.java  -- Worktree 隔离
  S19McpPlugin.java          -- MCP 插件
  SFullAgent.java            -- 完整集成参考
```

每个类都是**完全自包含**的——不依赖 `com.example.agent.*` 下的其他类。所有基础设施（客户端构建、工具定义、状态管理、输出格式化）全部内联实现。

外部依赖统一通过 Maven 管理：

```text
com.anthropic.*           -- Anthropic Java SDK
com.fasterxml.jackson.*   -- JSON 处理
io.github.cdimascio.dotenv -- 环境变量加载
java standard library     -- JDK 21+ 标准库
```

## 真正的映射关系

| 参考仓库模块簇 | 典型例子 | Java 教学章节 | 内部类/核心结构 | 为什么这样放是对的 |
|---|---|---|---|---|
| 查询主循环 + 控制状态 | `Tool.ts`、`AppStateStore.ts`、query / coordinator 状态 | `s00`、`s00a`、`s00b`、`s01`、`s11` | `QueryState`（s00a桥接）、`LoopState`（s01） | 真实系统绝不只是 `List<Message> + while (true)`。教学上先讲最小循环，再补控制平面，是对的。 |
| 工具路由与执行面 | `Tool.ts`、原生 tools、tool context | `s02`、`s02a`、`s02b` | `safePath()`、`runRead()`/`runWrite()`/`runEdit()`、`TOOL_HANDLERS`（Map） | 参考仓库明确把 tools 做成统一执行面。Java 版用 `Map<String, Function<...>>` 实现分发表。 |
| 会话规划 | `TodoWriteTool` | `s03` | `PlanItem`（record）、`PlanningState`、`TodoManager` | 这是"当前会话怎么不乱撞"的小结构，应该早于持久任务图。 |
| 一次性委派 | `AgentTool` 的最小子集 | `s04` | `AgentTemplate`（record）、`runSubagent()` | Java 版先教"新上下文 + 子任务 + 摘要返回"这个最小正确版本。 |
| 技能发现与按需加载 | `DiscoverSkillsTool`、`skills/*` | `s05` | `SkillManifest`（record）、`SkillDocument`（record）、`SkillRegistry` | 技能不是花哨外挂，而是知识注入层，应早于 prompt 复杂化和上下文压力。 |
| 上下文压力与压缩 | `toolUseSummary/*`、`contextCollapse/*` | `s06` | `CompactState`、`persistLargeOutput()`、`microCompact()`、`compactHistory()` | 显式压缩机制放在平台化能力之前完全正确。 |
| 权限闸门 | `types/permissions.ts`、`hooks/toolPermission/*` | `s07` | `BashSecurityValidator`、`PermissionManager` | 执行安全是明确闸门，必须早于 hook。Java 版用枚举 `PermissionDecision` 表达判定结果。 |
| Hook 与侧边扩展 | `types/hooks.ts`、hook runner | `s08` | `HookManager`、`HookPoint`（枚举） | 扩展点和权限分开。教学顺序保持"先 gate，再 extend"。 |
| 持久记忆选择 | `memdir/*`、`SessionMemory/*` | `s09` | `MemoryManager`、`DreamConsolidator` | Java 版把 memory 处理成"跨会话、选择性装配"的层。 |
| Prompt 组装 | `prompts.ts`、prompt sections | `s10`、`s10a` | `SystemPromptBuilder`、`buildSystemReminder()` | 输入拆成多个 section，讲成流水线而不是一段大字符串。 |
| 恢复与续行 | query transition、retry 分支 | `s11`、`s00c` | `estimateTokens()`、`autoCompact()`、`backoffDelay()` | "为什么继续下一轮"是显式存在的，所以恢复应晚于 loop / tools / compact / permissions / memory / prompt。 |
| 持久工作图 | 任务记录、任务板、依赖解锁 | `s12` | `TaskManager`、`TaskRecord`（record） | "持久任务目标"和"会话内待办"分开。 |
| 活着的运行时任务 | `tasks/types.ts`、`LocalShellTask`、`LocalAgentTask` | `s13`、`s13a` | `NotificationQueue`、`BackgroundManager` | runtime task 是明确独立状态。`TaskRecord` 和运行时槽位必须分开教。 |
| 定时触发 | `ScheduleCronTool/*` | `s14` | `CronLock`、`CronScheduler`、`cronMatches()` | 调度建在 runtime work 之上的新启动条件。 |
| 持久队友 | `InProcessTeammateTask`、team tools | `s15` | `MessageBus`、`TeammateManager` | 从一次性 subagent 继续长成长期 actor。 |
| 结构化团队协作 | send-message 流、request tracking | `s16` | `RequestStore`、`TeammateManager` | 协议建立在"已有持久 actor"之上。 |
| 自治认领与恢复 | coordinator mode、任务认领 | `s17` | `isClaimableTask()`、`claimTask()`、`ensureIdentityContext()` | 自治建立在 actor、任务和协议之上。 |
| Worktree 执行车道 | `EnterWorktreeTool`、`ExitWorktreeTool` | `s18` | `TaskManager`、`WorktreeManager`、`EventBus` | worktree 当作执行边界 + 收尾状态。 |
| 外部能力总线 | `MCPTool`、`services/mcp/*`、`plugins/*` | `s19`、`s19a` | `CapabilityPermissionGate`、`MCPClient`、`PluginLoader`、`MCPToolRouter` | MCP / plugin 放在平台最外层边界。 |

## 这份对照最能证明的 5 件事

### 1. `s03` 应该继续放在 `s12` 前面

参考仓库里同时存在：

- 小范围的会话计划（`TodoWriteTool`）
- 大范围的持久任务 / 运行时系统（`tasks/types.ts`）

它们不是一回事。

所以教学顺序应当继续保持：

`会话内计划 (S03TodoWrite.java) -> 持久任务图 (S12TaskSystem.java)`

### 2. `s09` 应该继续放在 `s10` 前面

参考仓库里的输入装配，明确把 memory 当成输入来源之一。

也就是说：

- `S09MemorySystem.java` 先回答"内容从哪里来"
- `S10SystemPrompt.java` 再回答"这些内容怎么组装进去"

所以先讲 `s09`，再讲 `s10`，顺序不要反过来。

### 3. `s12` 必须早于 `s13`

`tasks/types.ts` 这类运行时任务联合类型，是这次对照里最强的证据之一。

它非常清楚地说明：

- 持久化的工作目标（`TaskRecord`）
- 当前活着的执行槽位（`BackgroundManager`）

必须是两层不同状态。

如果先讲 `s13`（`S13BackgroundTasks.java`），读者几乎一定会把这两层混掉。

### 4. `s15 -> s16 -> s17` 的顺序是对的

参考仓库里明确能看到：

- 持久 actor（`S15AgentTeams.java` / `TeammateManager`）
- 结构化协作（`S16TeamProtocols.java` / `RequestStore`）
- 自治认领 / 恢复（`S17AutonomousAgents.java` / `claimTask()`）

自治必须建立在前两者之上，所以当前顺序合理。

### 5. `s18` 应该继续早于 `s19`

参考仓库把 worktree 当作本地执行边界机制。

这应该先于：

- 外部能力提供者
- MCP server
- plugin 装配面

被讲清。

否则读者会误以为"外部能力系统比本地执行边界更核心"。

## Java 版本的特殊设计选择

### 1. 完全自包含 vs 模块化引用

Python 原版的教学仓库中，每个文件可以自然地引用其他文件。

Java 版本选择了**完全自包含**的设计：

- 每个 `SXX*.java` 文件都不依赖其他 session 类
- 所有基础设施（客户端构建、JSON 处理、路径安全、输出格式化）全部内联
- 这导致每个文件都有一定量的重复代码，但保证了独立可运行

这个选择在教学上是正确的：

- 读者可以直接 `javac` + `java` 运行任意章节
- 不需要理解项目级依赖关系
- 可以按任意顺序学习

### 2. Java 21 特性的运用

Java 版本在教学代码中合理运用了现代 Java 特性：

- **record**：用于不可变数据结构（`PlanItem`、`TaskRecord`、`SkillManifest`）
- **sealed interface + pattern matching**：用于工具结果类型（`ToolResult`）
- **var**：减少样板代码，提高可读性
- **text block**：用于多行字符串（system prompt 模板）
- **virtual threads**：用于后台任务（`S13BackgroundTasks.java`）
- **switch expression**：用于工具路由分发表
- **Map.of() / List.of()**：不可变集合工厂方法

### 3. 依赖管理

所有外部依赖通过 Maven `pom.xml` 统一管理：

```xml
<dependencies>
    <dependency>com.anthropic:anthropic-sdk-java</dependency>
    <dependency>com.fasterxml.jackson.core:jackson-databind</dependency>
    <dependency>io.github.cdimascio:dotenv-java</dependency>
</dependencies>
```

这对应 Python 版的 `requirements.txt` / `pyproject.toml`。

## 这套教学仓库仍然不该抄进主线的内容

参考仓库里有很多真实但不应该占据主线的内容，例如：

- CLI 命令面的完整铺开
- UI 渲染细节
- 遥测与分析分支
- 远程 / 企业产品接线
- 平台兼容层
- 文件名、方法名、行号级 trivia

这些不是假的。

但它们不该成为 0 到 1 教学路径的中心。

## 当前教学最容易漂掉的地方

### 1. 不要把 subagent 和 teammate 混成一个模糊概念

参考仓库里的 `AgentTool` 横跨了：

- 一次性委派（`S04Subagent.java`）
- 后台 worker（`S13BackgroundTasks.java`）
- 持久 worker / teammate（`S15AgentTeams.java`）
- worktree 隔离 worker（`S18WorktreeIsolation.java`）

这恰恰说明教学仓库应该继续拆开讲：

- `s04`
- `s13`
- `s15`
- `s18`

不要在早期就把这些东西混成一个"大 agent 能力"。

### 2. 不要把 worktree 教成"只是 git 小技巧"

参考仓库里有 closeout、resume、cleanup、dirty-check 等状态。

所以 `S18WorktreeIsolation.java` 必须继续讲清：

- lane 身份
- task 绑定
- keep / remove 收尾
- 恢复与清理

而不是只讲 `git worktree add`。

### 3. 不要把 MCP 缩成"远程 tools"

参考仓库里明显不只有工具，还有：

- resources
- prompts
- elicitation / connection state
- plugin 中介层

所以 `S19McpPlugin.java` 可以继续用 tools-first 的教学路径切入，但一定要补平台边界那一层地图。

## 最终判断

如果只拿"章节顺序是否贴近参考仓库的设计主干"这个问题来打分，那么当前这套顺序是过关而且方向正确的。

真正还能继续加分的地方，不再是再做一次大重排，而是：

- 把桥接文档补齐（`s00a-s00f`）
- 把实体边界讲得更硬
- 让 Java 版本在保持自包含的同时，内部类之间的职责划分更清晰
- 确保每个 `SXX*.java` 文件中的内部类命名和结构，能直接对照到文档中的概念

## 一句话记住

**最好的教学顺序，不是源码文件出现的顺序，而是一个初学实现者真正能顺着依赖关系把系统重建出来的顺序。**
