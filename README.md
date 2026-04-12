# AI-Agent-ZeroToOne
从零到一写一个 Agent（Claude Code）

## learn-claude-code 项目学习

### 项目引入

通过 git submodule 引入了 [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) 项目作为学习参考：

```bash
git submodule add https://github.com/shareAI-lab/learn-claude-code vendors/learn-claude-code
```

### 核心设计哲学

**The Model IS the Agent** — 模型本身就是 Agent，代码只是提供运行环境（Harness）。

三个关键推论：
1. **Agent = Loop + Tools**：核心循环只有 5 行，其余都是可选增强
2. **Intelligence is in the model, not the code**：代码只负责执行工具调用，决策完全由模型完成
3. **Context is the agent's memory**：消息历史就是工作记忆

### 十二课学习路径

| 阶段 | 课程 | 主题 |
|------|------|------|
| Phase 1: The Loop | s01–s02 | 基础循环与工具分发 |
| Phase 2: Planning & Knowledge | s03–s06 | 规划、知识、压缩 |
| Phase 3: Persistence | s07–s08 | 任务系统与后台执行 |
| Phase 4: Teams | s09–s12 | 多 Agent 协作 |

### 核心机制一览

| 机制 | 说明 |
|------|------|
| **Agent Loop** | `while stop_reason==tool_use` 循环 |
| **Tool Dispatch** | `TOOL_HANDLERS` 字典分发 |
| **TodoManager** | 结构化任务追踪 + nag reminder |
| **Subagent** | 进程隔离 = 上下文隔离 |
| **SkillLoader** | 两层按需加载（元数据 → 完整内容）|
| **三层压缩** | micro / auto / manual |
| **TaskManager** | 文件系统持久化任务状态 |
| **BackgroundManager** | 线程池异步执行 |
| **MessageBus** | JSONL 邮箱式团队通信 |
| **Protocols** | shutdown / plan_approval 握手协议 |
| **Autonomous** | idle 轮询 + 自动任务认领 |
| **Worktree** | 目录级隔离 + git worktree |

### 详细文档

完整的架构分析请参阅 [learn-claude-code-arch.md](./specs/learn-claude-code-arch.md)

## mini-agent-4j 项目

### 项目概述

**mini-agent-4j** 是 learn-claude-code 的 Java 21 完整重写版本，用 19 节渐进式课程从零构建一个 AI 编码 Agent。

每个课程文件都是**完全自包含**的——不依赖其他包，所有基础设施（客户端构建、工具定义、命令执行、JsonValue 转换、ANSI 输出等）全部内联实现，只依赖 Anthropic Java SDK + dotenv-java。

### 核心数据

- **22 个 Java 源文件**，约 19,600 行代码
- **Java 21 现代特性**：Record、Switch Expression、Pattern Matching、Virtual Threads
- **19 节渐进式课程**：每课只增加一个新概念，独立可运行
- **最小依赖**：仅 anthropic-java SDK + dotenv-java
- **36 篇中文教学文档**：架构解析 + 逐课讲解

### 核心架构

```
核心理念：模型就是 Agent，开发者只需提供"装具"（Harness）
    ┌──────────┐      ┌───────┐      ┌──────────┐
    │  User    │ ───> │  LLM  │ ───> │  Tool    │
    │  prompt  │      │       │      │  execute │
    └──────────┘      └───┬───┘      └────┬─────┘
                          ^               │
                          │  tool_result   │
                          └───────────────┘
                          (loop continues)
```

### 课程大纲

| 课程 | 主题 | 新增概念 |
|------|------|----------|
| S01 | Agent Loop | while 循环 + LoopState + bash 工具 |
| S02 | Tool Use | 工具分发表 (dispatch map) + 4 工具 + normalizeMessages |
| S03 | TodoWrite | PlanningState + TodoManager + nag reminder |
| S04 | Subagent | 上下文隔离（独立 messages=[]）|
| S05 | Skill Loading | 两层技能注入（元数据 + 按需加载）|
| S06 | Context Compact | 三层压缩管线（micro/auto/manual）|
| S07 | Permission System | 权限模型与拦截 |
| S08 | Hook System | 生命周期钩子 |
| S09 | Memory System | 持久化记忆 |
| S10 | System Prompt | 系统提示词工程 |
| S11 | Error Recovery | 错误恢复与重试 |
| S12 | Task System | 文件持久化 DAG 任务系统 |
| S13 | Background Tasks | Virtual Thread 后台执行 + 通知队列 |
| S14 | Cron Scheduler | 定时任务调度 |
| S15 | Agent Teams | JSONL 邮箱 + 持久化 Teammate |
| S16 | Team Protocols | Shutdown / Plan Approval 握手协议 |
| S17 | Autonomous Agents | 空闲轮询 + 自动认领任务 |
| S18 | Worktree Isolation | Git Worktree 目录级隔离 |
| S19 | MCP Plugin | Model Context Protocol 插件 |
| SFull | Full Agent | 全量工具整合 |

### 核心机制一览

| 机制 | 说明 | 所在课程 |
|------|------|----------|
| **Agent Loop** | `while(runOneTurn(state))` 循环 | S01 |
| **LoopState** | turnCount + transitionReason + lastResponse | S01 |
| **Tool Dispatch** | `Map<String, Function>` 字典分发 | S02 |
| **TodoManager** | 结构化任务追踪 + nag reminder | S03 |
| **Subagent** | 进程隔离 = 上下文隔离 | S04 |
| **SkillLoader** | 两层按需加载（元数据 → 完整内容）| S05 |
| **三层压缩** | micro / auto / manual | S06 |
| **TaskManager** | 文件系统持久化任务状态 | S12 |
| **BackgroundManager** | Virtual Thread 线程池异步执行 | S13 |
| **CronScheduler** | 定时任务调度 | S14 |
| **MessageBus** | JSONL 邮箱式团队通信 | S15 |
| **TeamProtocol** | shutdown / plan_approval 握手协议 | S16 |
| **Autonomous** | idle 轮询 + 自动任务认领 | S17 |
| **Worktree** | 目录级隔离 + git worktree | S18 |
| **MCP Plugin** | Model Context Protocol 外部工具接入 | S19 |

### 项目结构

```
mini-agent-4j/
├── src/main/java/com/example/agent/
│   ├── Launcher.java                    # 统一启动入口
│   └── sessions/                        # 19 节课程（每课独立可运行）
│       ├── S01AgentLoop.java
│       ├── S02ToolUse.java
│       ├── S03TodoWrite.java
│       ├── S04Subagent.java
│       ├── S05SkillLoading.java
│       ├── S06ContextCompact.java
│       ├── S07PermissionSystem.java
│       ├── S08HookSystem.java
│       ├── S09MemorySystem.java
│       ├── S10SystemPrompt.java
│       ├── S11ErrorRecovery.java
│       ├── S12TaskSystem.java
│       ├── S13BackgroundTasks.java
│       ├── S14CronScheduler.java
│       ├── S15AgentTeams.java
│       ├── S16TeamProtocols.java
│       ├── S17AutonomousAgents.java
│       ├── S18WorktreeIsolation.java
│       ├── S19McpPlugin.java
│       └── SFullAgent.java
├── docs/cn/                             # 36 篇中文教学文档
│   ├── s00-architecture-overview.md     # 架构总览
│   ├── s01-the-agent-loop.md           # 逐课讲解
│   ├── ...                              # （共 36 篇）
│   └── glossary.md                      # 术语表
└── .env.example                         # 环境变量模板
```

### 快速开始

```bash
# 1. 进入项目目录
cd mini-agent-4j

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env，填入你的 API Key 和模型 ID

# 3. 编译
mvn compile

# 4. 运行任意课程（以 S01 为例）
mvn exec:java -Dexec.mainClass="com.example.agent.sessions.S01AgentLoop"

# 5. 运行全量 Agent
mvn exec:java -Dexec.mainClass="com.example.agent.sessions.SFullAgent"
```

### 详细文档

- [learn-claude-code-arch.md](./specs/learn-claude-code-arch.md) - Python 原版架构分析
- [java-rewrite-analysis.md](./specs/java-rewrite-analysis.md) - Python 到 Java 的重写分析
- [java-rewrite-impl-plan.md](./specs/java-rewrite-impl-plan.md) - 实现计划
- [java-rewrite-impl-summary.md](./specs/java-rewrite-impl-summary.md) - 实现总结
- [implementation-plan.md](./specs/implementation-plan.md) - 实现路线图
