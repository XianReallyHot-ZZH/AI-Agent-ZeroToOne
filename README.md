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






