# AI-Agent-ZeroToOne
从零到一写一个agent（claude-code）

## learn-claude-code 项目学习

### 项目初探与架构解析

#### 项目引入

通过 git submodule 引入了 [learn-claude-code](https://github.com/shareAI-lab/learn-claude-code) 项目作为学习参考：

```bash
git submodule add https://github.com/shareAI-lab/learn-claude-code vendors/learn-claude-code
```

#### 核心设计哲学

**The Model IS the Agent** — 模型本身就是 Agent，代码只是提供运行环境（Harness）。

三个关键推论：
1. **Agent = Loop + Tools**：核心循环只有 5 行，其余都是可选增强
2. **Intelligence is in the model, not the code**：代码只负责执行工具调用，决策完全由模型完成
3. **Context is the agent's memory**：消息历史就是工作记忆

#### 十二课学习路径

| 阶段 | 课程 | 主题 |
|------|------|------|
| Phase 1: The Loop | s01–s02 | 基础循环与工具分发 |
| Phase 2: Planning & Knowledge | s03–s06 | 规划、知识、压缩 |
| Phase 3: Persistence | s07–s08 | 任务系统与后台执行 |
| Phase 4: Teams | s09–s12 | 多 Agent 协作 |

#### 核心机制一览

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

#### 详细文档

完整的架构分析请参阅 [learn-claude-code-arch.md](./specs/learn-claude-code-arch.md)

## mini-agent-4j 项目

### 项目概述

**mini-agent-4j** 是 learn-claude-code 的 Java 21 完整重写版本，用 12 节渐进式课程从零构建一个 AI 编码 Agent。

#### 核心数据

- **34 个 Java 源文件**，约 4600 行代码
- **Java 21 现代特性**：Virtual Threads、Record、Switch Expression、Pattern Matching
- **13 节渐进式课程**：每课只增加一个新概念，独立可运行
- **最小依赖**：仅 anthropic-java SDK + Jackson + dotenv-java

#### 核心架构

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
| S01 | Agent Loop | while 循环 + bash 工具 |
| S02 | Tool Use | 工具分发表 (dispatch map) |
| S03 | TodoWrite | 结构化计划跟踪 + nag reminder |
| S04 | Subagent | 上下文隔离（独立 messages=[]）|
| S05 | Skill Loading | 两层技能注入（元数据 + 按需加载）|
| S06 | Context Compact | 三层压缩管线（micro/auto/manual）|
| S07 | Task System | 文件持久化 DAG 任务系统 |
| S08 | Background Tasks | Virtual Thread 后台执行 + 通知队列 |
| S09 | Agent Teams | JSONL 邮箱 + 持久化 Teammate |
| S10 | Team Protocols | Shutdown / Plan Approval 握手协议 |
| S11 | Autonomous Agents | 空闲轮询 + 自动认领任务 |
| S12 | Worktree Isolation | Git Worktree 目录级隔离 |
| SFull | Full Agent | 22 个工具全量整合 |

### 核心机制一览

| 机制 | 说明 | Java 实现 |
|------|------|-----------|
| **Agent Loop** | `while(stopReason==TOOL_USE)` 循环 | `AgentLoop.java` |
| **Tool Dispatch** | `Map<String, ToolHandler>` 字典分发 | `ToolDispatcher.java` |
| **TodoManager** | 结构化任务追踪 + nag reminder | `TodoManager.java` |
| **Subagent** | 进程隔离 = 上下文隔离 | `S04Subagent.java` |
| **SkillLoader** | 两层按需加载（元数据 → 完整内容）| `SkillLoader.java` |
| **三层压缩** | micro / auto / manual | `ContextCompressor.java` |
| **TaskManager** | 文件系统持久化任务状态 | `TaskManager.java` |
| **BackgroundManager** | Virtual Thread 线程池异步执行 | `BackgroundManager.java` |
| **MessageBus** | JSONL 邮箱式团队通信 | `MessageBus.java` |
| **TeamProtocol** | shutdown / plan_approval 握手协议 | `TeamProtocol.java` |
| **Autonomous** | idle 轮询 + 自动任务认领 | `S11AutonomousAgents.java` |
| **Worktree** | 目录级隔离 + git worktree | `WorktreeManager.java` |

### 项目结构

```
mini-agent-4j/
├── src/main/java/com/example/agent/
│   ├── core/                    # 核心：Agent 循环 + 工具分发
│   │   ├── AgentLoop.java       #   while(TOOL_USE) 核心循环
│   │   ├── ToolHandler.java     #   @FunctionalInterface 工具接口
│   │   └── ToolDispatcher.java  #   Map<String, ToolHandler> 路由
│   ├── tools/                   # 4 个基础工具
│   │   ├── BashTool.java        #   ProcessBuilder 命令执行
│   │   ├── ReadTool.java        #   文件读取（支持行数限制）
│   │   ├── WriteTool.java       #   文件写入（自动建目录）
│   │   └── EditTool.java        #   精确文本替换
│   ├── util/                    # 基础设施
│   ├── tasks/                   # 任务管理
│   ├── skills/                  # 技能系统
│   ├── compress/                # 上下文压缩
│   ├── background/              # 后台执行
│   ├── team/                    # 多 Agent 团队
│   ├── worktree/                # Git Worktree 隔离
│   └── sessions/                # 13 节课程（独立可运行）
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

完整的 Java 重写分析和实现细节请参阅：
- [java-rewrite-analysis.md](./specs/java-rewrite-analysis.md) - Python 到 Java 的重写分析
- [java-rewrite-impl-plan.md](./specs/java-rewrite-impl-plan.md) - 实现计划
- [java-rewrite-impl-summary.md](./specs/java-rewrite-impl-summary.md) - 实现总结






