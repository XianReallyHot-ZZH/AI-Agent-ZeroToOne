# Implementation Plan: Sync mini-agent-4j with learn-claude-code

## Context

The Python project `learn-claude-code` has been significantly updated with 7 new sessions (s07-s11, s14, s19) that the Java project `mini-agent-4j` is missing. Additionally, the existing Java sessions have different numbering than Python (Java S07-S12 correspond to Python s12-s18). The user wants full numbering alignment and feature parity.

## Core Design Principle: Self-Contained Sessions

**Every session file must be completely self-contained.**

Python 原版项目已验证：所有 20 个文件（s01-s19 + s_full）都是完全自包含的，没有任何文件从项目内其他文件导入。这是有意为之的教学设计——每一课都是独立的一课。

Java 版本必须遵循同样的原则：

| 自包含要求 | 说明 |
|-----------|------|
| 无项目内导入 | 每个 Session 类不 import 其他 Session 或共享业务类（仅允许 import 标准库 + anthropic-sdk + jackson + dotenv） |
| 内联工具实现 | 每个 Session 定义自己的 `safePath()`、`runBash()`、`runRead()`、`runWrite()`、`runEdit()` |
| 内联 Agent 循环 | 每个 Session 定义自己的 `agentLoop()`，即使与前面 Session 相同也要重新定义 |
| 内联工具定义 | 每个 Session 定义自己的 Tool 列表和 ToolHandler 分发逻辑 |
| 内联辅助类 | 所有辅助类（如 PermissionManager、HookManager 等）都在 Session 文件内部定义为 private static 内部类或方法 |
| 独立入口 | 每个 Session 有自己的 `public static void main(String[] args)` 方法 |

### 现有 Java Session 自包含状态分析

经过代码审查，**所有 13 个现有 Java Session 都不是自包含的**。以下是每个文件的外部依赖：

| Session | 外部依赖的共享包 | 需要内联的内容 |
|---------|----------------|--------------|
| **S01AgentLoop** | core.AgentLoop, core.ToolDispatcher, tools.BashTool, util.Console | agentLoop 循环体、Tool 分发、runBash()、ANSI 输出 |
| **S02ToolUse** | core.AgentLoop, core.ToolDispatcher, tools(Bash/Read/Write/Edit), util.Console, util.PathSandbox | +runRead/Write/Edit, safePath |
| **S03TodoWrite** | core.AgentLoop, core.ToolDispatcher, tools(4), tasks.TodoManager, util.Console, util.PathSandbox | +TodoManager 内部类 |
| **S04Subagent** | core.AgentLoop, core.ToolDispatcher, tools(4), util.Console, util.PathSandbox | 已有 runSubagent() 内联，但主循环仍依赖 AgentLoop |
| **S05SkillLoading** | core.AgentLoop, core.ToolDispatcher, tools(4), skills.SkillLoader, util.Console, util.PathSandbox | +SkillLoader 内部类 |
| **S06ContextCompact** | core.AgentLoop, core.ToolDispatcher, tools(4), compress.ContextCompressor, util.Console, util.PathSandbox | 已有自定义循环，但仍依赖工具类和 ContextCompressor |
| **S07TaskSystem** | core.AgentLoop, core.ToolDispatcher, tools(4), tasks.TaskManager, util.Console, util.PathSandbox | +TaskManager 内部类 |
| **S08BackgroundTasks** | core.AgentLoop, core.ToolDispatcher, tools(4), background.BackgroundManager, util.Console, util.PathSandbox | +BackgroundManager 内部类 |
| **S09AgentTeams** | core.AgentLoop, core.ToolDispatcher, tools(4), team.MessageBus, team.TeamManager, util.Console, util.PathSandbox | +MessageBus + TeamManager 内部类 |
| **S10TeamProtocols** | core.AgentLoop, core.ToolDispatcher, tools(4), team.MessageBus, util.Console, util.PathSandbox | 已内联协议/团队逻辑，但仍依赖基础工具 |
| **S11AutonomousAgents** | core.AgentLoop, core.ToolDispatcher, tools(4), team.MessageBus, util.Console, util.PathSandbox | 已内联任务/团队/协议，但仍依赖基础工具 |
| **S12WorktreeIsolation** | core.AgentLoop, core.ToolDispatcher, tools(4), util.Console, util.PathSandbox | 已内联任务/工作树/事件，但仍依赖基础工具 |
| **SFullAgent** | core, tools(4), util(3), tasks.TodoManager, skills.SkillLoader, compress.ContextCompressor, background.BackgroundManager, team.MessageBus | 已内联大部分逻辑，但仍依赖大量共享包 |

**所有 Session 共享的通用外部依赖**（需要内联到每个文件）：

```
core.AgentLoop        → while(stopReason == TOOL_USE) 循环 + defineTool() 辅助方法
core.ToolDispatcher   → Map<String, Function> 工具分发
tools.BashTool        → runBash(command, workDir) 实现
tools.ReadTool        → runRead(path, limit) 实现
tools.WriteTool       → runWrite(path, content) 实现
tools.EditTool        → runEdit(path, oldText, newText) 实现
util.Console          → ANSI 颜色输出辅助方法
util.PathSandbox      → safePath(path, workDir) 路径安全检查
util.EnvLoader        → .env 文件加载 + API Key/Model/BaseUrl 读取
```

**各 Session 特有的外部依赖**（需要内联为内部类）：

```
S03: tasks.TodoManager         → TodoManager 内部类
S05: skills.SkillLoader        → SkillLoader 内部类
S06: compress.ContextCompressor → 压缩相关方法
S07: tasks.TaskManager         → TaskManager 内部类
S08: background.BackgroundManager → BackgroundManager 内部类
S09: team.MessageBus + team.TeamManager → MessageBus + TeamManager 内部类
S10: team.MessageBus           → MessageBus 内部类
S11: team.MessageBus           → MessageBus 内部类
SFull: tasks.TodoManager, skills.SkillLoader, compress.ContextCompressor, background.BackgroundManager, team.MessageBus
```

### 自包含改造策略

对每个现有 Session 的改造遵循以下模板：

```java
package com.example.agent.sessions;

// 仅允许的外部导入
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class SXXName {
    private static final Path WORKDIR = Path.of("").toAbsolutePath();

    // ---- 基础设施（内联） ----
    private static AnthropicClient buildClient() { /* 加载 .env，构建客户端 */ }
    private static String loadEnv(String key, String def) { /* 读取环境变量 */ }
    private static Path safePath(String p) { /* 路径安全检查 */ }
    private static void printToolCall(String name, String summary) { /* ANSI 彩色输出 */ }
    private static Tool defineTool(String name, String desc,
                                   Map<String,Object> props, List<String> required) { /* 构建 Tool 定义 */ }

    // ---- 工具实现（内联） ----
    private static String runBash(String command) { ... }
    private static String runRead(String path, Integer limit) { ... }
    private static String runWrite(String path, String content) { ... }
    private static String runEdit(String path, String oldText, String newText) { ... }

    // ---- Agent 循环（内联，使用 SDK 类型） ----
    // 消息格式使用 MessageCreateParams.Builder（SDK 类型），不用 Map
    private static void agentLoop(MessageCreateParams.Builder params, ...) {
        while (true) {
            Message response = client.messages().create(params.build());
            params.addMessage(response);
            if (!StopReason.TOOL_USE.equals(response.stopReason().orElse(null))) {
                // 打印文本回复并返回
                return;
            }
            // 执行工具、收集结果、追加为 user 消息
            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                if (block.isToolUse()) {
                    ToolUseBlock toolUse = block.asToolUse();
                    String output = dispatch(toolUse.name(), extractInput(toolUse));
                    results.add(ContentBlockParam.ofToolResult(...));
                }
            }
            params.addUserMessageOfBlockParams(results);
        }
    }

    // ---- Session 特有的辅助类（作为 private static 内部类） ----
    // 例: private static class TodoManager { ... }

    // ---- REPL 入口 ----
    public static void main(String[] args) { ... }
}
```

> **共享包处理决策**：改造完成后，**全部删除** `core/`、`tools/`、`util/`、`tasks/`、`skills/`、`compress/`、`background/`、`team/`、`worktree/` 包。最终项目结构中 `com.example.agent` 下只保留 `sessions/` 和 `Launcher.java`。

### 技术决策记录

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 消息格式 | **SDK 类型**（MessageCreateParams.Builder） | 更 Java 风格，利用 SDK 类型安全；不可变对象的限制通过每次 rebuild params 解决 |
| 共享包 | **全部删除** | 消除死代码，避免学习者困惑 |
| pom.xml 依赖 | **清理 picocli + logback** | picocli 从未使用，logback 在自包含后不再需要 |
| 文档范围 | **更新 README.md** | 反映 19 个 Session + SFull 的完整课程；不增加日语文档、不移植测试 |

## Changes Overview

### 1. Renumber existing sessions (Java S07-S12 → S12-S18)

| Current Java | New Java | Python equivalent | Action |
|-------------|----------|-------------------|--------|
| S07TaskSystem | S12TaskSystem | s12_task_system | Rename file + class, update prompt `s07>>` → `s12>>` |
| S08BackgroundTasks | S13BackgroundTasks | s13_background_tasks | Rename file + class, update prompt `s08>>` → `s13>>` |
| S09AgentTeams | S15AgentTeams | s15_agent_teams | Rename file + class, update prompt `s09>>` → `s15>>` |
| S10TeamProtocols | S16TeamProtocols | s16_team_protocols | Rename file + class, update prompt `s10>>` → `s16>>` |
| S11AutonomousAgents | S17AutonomousAgents | s17_autonomous_agents | Rename file + class, update prompt `s11>>` → `s17>>` |
| S12WorktreeIsolation | S18WorktreeIsolation | s18_worktree_task_isolation | Rename file + class, update prompt `s12>>` → `s18>>` |

### 2. Create new session files (all self-contained)

| New Java Session | Python source | Key features | 预估行数 |
|-----------------|---------------|-------------|---------|
| S07PermissionSystem | s07 | Permission pipeline (deny→mode→allow→ask)，内联 BashSecurityValidator + PermissionManager，3 种模式 (default/plan/auto)，REPL: /mode /rules | ~350 |
| S08HookSystem | s08 | 内联 HookManager，PreToolUse/PostToolUse/SessionStart 事件，exit code 契约 (0=continue, 1=block, 2=inject)，workspace trust 检查 | ~300 |
| S09MemorySystem | s09 | 内联 MemoryManager（frontmatter .md 存储）+ DreamConsolidator，4 种记忆类型，MEMORY.md 索引，REPL: /memories | ~450 |
| S10SystemPrompt | s10 | 内联 SystemPromptBuilder，6 个组装段落 (core/tools/skills/memory/CLAUDE.md/dynamic)，DYNAMIC_BOUNDARY 标记，REPL: /prompt /sections | ~350 |
| S11ErrorRecovery | s11 | 3 种恢复策略：max_tokens 续写、prompt_too_long→压缩、connection→退避重试，指数退避 + 随机抖动 | ~280 |
| S14CronScheduler | s14 | 内联 CronLock + CronScheduler，后台线程，5 字段 cron 匹配，session-only/durable 模式，recurring/one-shot，7 天自动过期，REPL: /cron /test | ~450 |
| S19McpPlugin | s19 | 内联 MCPClient (stdio JSON-RPC) + MCPToolRouter + PluginLoader + CapabilityPermissionGate，mcp__ 前缀路由，REPL: /tools /mcp | ~480 |

每个新 Session 内部需要定义的所有内容（以内联方式）：

```
S07PermissionSystem:
  ├── BashSecurityValidator (内部类) — 正则匹配危险 Bash 命令
  ├── PermissionManager (内部类) — deny→mode→allow→ask 管线
  ├── safePath(), runBash(), runRead(), runWrite(), runEdit() (静态方法)
  ├── TOOLS 列表 + TOOL_HANDLERS Map
  ├── agentLoop() — 带权限检查的 Agent 循环
  └── main() — REPL 入口 (/mode, /rules)

S08HookSystem:
  ├── HookManager (内部类) — 从 .hooks.json 加载并执行钩子
  ├── safePath(), runBash(), runRead(), runWrite(), runEdit()
  ├── TOOLS + TOOL_HANDLERS
  ├── agentLoop() — 带 PreToolUse/PostToolUse 钩子的循环
  └── main()

S09MemorySystem:
  ├── MemoryManager (内部类) — .memory/ 目录读写 + MEMORY.md 索引
  ├── DreamConsolidator (内部类) — 7 门控检查 + 4 阶段合并
  ├── safePath(), runBash(), runRead(), runWrite(), runEdit(), runSaveMemory()
  ├── TOOLS + TOOL_HANDLERS (含 save_memory)
  ├── buildSystemPrompt() — 注入记忆内容的系统提示词
  ├── agentLoop()
  └── main() (/memories)

S10SystemPrompt:
  ├── SystemPromptBuilder (内部类) — 6 段落组装
  │     ├── _buildCore() — 核心指令
  │     ├── _buildToolListing() — 工具列表
  │     ├── _buildSkillListing() — 技能元数据
  │     ├── _buildMemorySection() — 记忆内容
  │     ├── _buildClaudeMd() — CLAUDE.md 链
  │     └── _buildDynamicContext() — 动态上下文（日期、工作目录、模型、平台）
  ├── safePath(), runBash(), runRead(), runWrite(), runEdit()
  ├── TOOLS + TOOL_HANDLERS
  ├── agentLoop() — 每次迭代重建系统提示词
  └── main() (/prompt, /sections)

S11ErrorRecovery:
  ├── estimateTokens(), autoCompact(), backoffDelay() (静态方法)
  ├── safePath(), runBash(), runRead(), runWrite(), runEdit()
  ├── TOOLS + TOOL_HANDLERS
  ├── agentLoop() — 带 3 种恢复策略的循环
  │     ├── max_tokens → 注入续写消息，最多重试 3 次
  │     ├── prompt_too_long → 触发 autoCompact 后重试
  │     └── connection error → 指数退避 (base * 2^attempt + jitter)
  └── main()

S14CronScheduler:
  ├── CronLock (内部类) — PID 文件锁
  ├── CronScheduler (内部类) — 后台线程 + cron 表达式匹配
  │     ├── cronMatches() — 5 字段匹配 (*, */N, N-M, N,M)
  │     ├── create(), delete(), listTasks(), drainNotifications()
  │     ├── detectMissedTasks() — 启动时检查遗漏任务
  │     └── _checkLoop() — 每秒检查的后台线程
  ├── safePath(), runBash(), runRead(), runWrite(), runEdit()
  ├── TOOLS + TOOL_HANDLERS (含 cron_create, cron_delete, cron_list)
  ├── agentLoop() — 每轮注入 cron 通知
  └── main() (/cron, /test)

S19McpPlugin:
  ├── CapabilityPermissionGate (内部类) — 统一权限门（native + MCP）
  ├── MCPClient (内部类) — stdio JSON-RPC 2.0 客户端
  │     ├── connect() — 启动子进程 + initialize 握手
  │     ├── listTools() — 获取服务器工具列表
  │     ├── callTool() — 执行远程工具
  │     └── getAgentTools() — 转换为 mcp__ 前缀格式
  ├── MCPToolRouter (内部类) — mcp__ 前缀路由
  ├── PluginLoader (内部类) — 从 .claude-plugin/plugin.json 发现插件
  ├── safePath(), runBash(), runRead(), runWrite(), runEdit()
  ├── NATIVE_TOOLS + NATIVE_HANDLERS
  ├── buildToolPool() — 合并 native + MCP 工具
  ├── handleToolCall() — 分发到 native 或 MCP
  ├── normalizeToolResult() — 统一结果格式
  ├── agentLoop() — 带权限检查的统一工具池循环
  └── main() (/tools, /mcp, 清理 MCP 连接)
```

### 3. Update SFullAgent

在现有 SFullAgent 基础上新增 6 个机制（保持自包含）：

- **Already has**: agent loop, tool dispatch, todos, subagent, skills, compression, tasks, background, messaging, team protocols, autonomous agents
- **New additions needed** (以内联方式):
  - PermissionManager (来自 S07) — 工具调用权限管线
  - HookManager (来自 S08) — 钩子扩展点
  - MemoryManager (来自 S09) — 跨会话记忆 + 系统提示词注入
  - SystemPromptBuilder (来自 S10) — 系统提示词组装管线
  - Error recovery logic (来自 S11) — max_tokens/compact/backoff 恢复
  - CronScheduler (来自 S14) — 定时任务调度

预估 SFullAgent 总行数：~1800-2200 行

### 4. Update Launcher.java

更新 session 映射：
```
s01-s06: 不变
s07: S07PermissionSystem
s08: S08HookSystem
s09: S09MemorySystem
s10: S10SystemPrompt
s11: S11ErrorRecovery
s12: S12TaskSystem (从 S07 重编号)
s13: S13BackgroundTasks (从 S08 重编号)
s14: S14CronScheduler
s15: S15AgentTeams (从 S09 重编号)
s16: S16TeamProtocols (从 S10 重编号)
s17: S17AutonomousAgents (从 S11 重编号)
s18: S18WorktreeIsolation (从 S12 重编号)
s19: S19McpPlugin
full: SFullAgent
```

### 5. Copy skills directory

Copy `vendors/learn-claude-code/skills/` → `mini-agent-4j/skills/`
Includes: agent-builder, code-review, mcp-builder, pdf

### 6. Update documentation

Create Chinese docs for the 7 new sessions in `docs/cn/`:
- s07-permission-system.md
- s08-hook-system.md
- s09-memory-system.md
- s10-system-prompt.md
- s11-error-recovery.md
- s14-cron-scheduler.md
- s19-mcp-plugin.md

Create English docs for the 7 new sessions in `docs/en/`:
- s07-permission-system.md
- s08-hook-system.md
- s09-memory-system.md
- s10-system-prompt.md
- s11-error-recovery.md
- s14-cron-scheduler.md
- s19-mcp-plugin.md

Renumber existing docs to match new numbering.

## Implementation Order

### Phase 1: 改造现有 Session 为自包含 (S01-S06)

优先改造 S01-S06，因为它们是教学课程的起点，且改造后可以作为后续 Session 的参考模板。

| 顺序 | Session | 改造要点 |
|------|---------|---------|
| 1.1 | **S01AgentLoop** | 内联: agentLoop 循环体、defineTool()、runBash()、buildClient()、loadModel()、ANSI 输出 |
| 1.2 | **S02ToolUse** | 内联: +safePath()、runRead/Write/Edit()、工具分发 Map + normalizeMessages() |
| 1.3 | **S03TodoWrite** | 内联: +TodoManager 内部类、RoundHook 回调 |
| 1.4 | **S04Subagent** | 内联: +独立 subagent 循环（已有部分内联，需移除 AgentLoop 依赖） |
| 1.5 | **S05SkillLoading** | 内联: +SkillLoader 内部类、YAML frontmatter 解析 |
| 1.6 | **S06ContextCompact** | 内联: +压缩相关方法（microCompact、autoCompact、estimateTokens），Map 消息格式 |

### Phase 2: 创建新 Session (S07-S11, S14, S19) — 全部自包含

| 顺序 | Session | Python source |
|------|---------|---------------|
| 2.1 | **S07PermissionSystem** | s07_permission_system.py |
| 2.2 | **S08HookSystem** | s08_hook_system.py |
| 2.3 | **S09MemorySystem** | s09_memory_system.py |
| 2.4 | **S10SystemPrompt** | s10_system_prompt.py |
| 2.5 | **S11ErrorRecovery** | s11_error_recovery.py |
| 2.6 | **S14CronScheduler** | s14_cron_scheduler.py |
| 2.7 | **S19McpPlugin** | s19_mcp_plugin.py |

### Phase 3: 重命名并改造现有 Session (S07-S12 → S12-S18)

每个文件同时进行重命名 + 自包含改造：

| 顺序 | 原文件 | 新文件 | 额外内联内容 |
|------|--------|--------|-------------|
| 3.1 | S07TaskSystem | **S12TaskSystem** | TaskManager 内部类 |
| 3.2 | S08BackgroundTasks | **S13BackgroundTasks** | BackgroundManager 内部类 + NotificationQueue |
| 3.3 | S09AgentTeams | **S15AgentTeams** | MessageBus + TeamManager 内部类 |
| 3.4 | S10TeamProtocols | **S16TeamProtocols** | MessageBus 内部类（协议逻辑已内联） |
| 3.5 | S11AutonomousAgents | **S17AutonomousAgents** | MessageBus 内部类（任务/团队已内联） |
| 3.6 | S12WorktreeIsolation | **S18WorktreeIsolation** | （任务/工作树已内联，只需移除工具包依赖） |

### Phase 4: 更新 SFullAgent

完全重写 SFullAgent，结合所有 18 个机制：
- 移除所有 `com.example.agent.*` 导入
- 内联所有基础设施（buildClient、safePath、工具实现、agentLoop）
- 内联所有辅助类（TodoManager、SkillLoader、TaskManager、BackgroundManager、MessageBus、TeammateManager）
- 新增：PermissionManager、HookManager、MemoryManager、SystemPromptBuilder、ErrorRecovery、CronScheduler

### Phase 5: 收尾

| 顺序 | 任务 |
|------|------|
| 5.1 | **更新 Launcher.java** — 新增映射 + 重编号 |
| 5.2 | **复制 skills 目录** — 从 Python 项目复制到 mini-agent-4j/skills/ |
| 5.3 | **删除共享包** — 删除 core/、tools/、util/、tasks/、skills/、compress/、background/、team/、worktree/ 目录 |
| 5.4 | **清理 pom.xml** — 移除 picocli 和 logback 依赖 |
| 5.5 | **更新 README.md** — 反映 19 个 Session + SFull 的完整课程结构 |
| 5.6 | **更新文档** — 新增中文/英文文档，重编号现有文档 |

## Verification

After implementation:
1. `mvn compile` — ensure all files compile without errors
2. `mvn package` — ensure fat jar builds successfully
3. Run `java -jar mini-agent-4j.jar s07` through `s19` to verify each session loads correctly
4. Run `java -jar mini-agent-4j.jar full` to verify the capstone session loads
5. Verify Launcher lists all sessions correctly

## Final Project Structure

改造完成后的项目结构：

```
mini-agent-4j/
├── pom.xml                              # Maven 配置（已清理无用依赖）
├── README.md                            # 项目文档（已更新）
├── .env.example                         # 环境变量模板
├── .gitignore
├── specs/
│   └── implementation-plan.md           # 本实现计划
├── skills/                              # 从 Python 项目复制
│   ├── agent-builder/SKILL.md
│   ├── code-review/SKILL.md
│   ├── mcp-builder/SKILL.md
│   └── pdf/SKILL.md
├── src/main/
│   ├── java/com/example/agent/
│   │   ├── Launcher.java                # 唯一的入口类
│   │   └── sessions/
│   │       ├── S01AgentLoop.java        # 自包含
│   │       ├── S02ToolUse.java          # 自包含
│   │       ├── S03TodoWrite.java        # 自包含
│   │       ├── S04Subagent.java         # 自包含
│   │       ├── S05SkillLoading.java     # 自包含
│   │       ├── S06ContextCompact.java   # 自包含
│   │       ├── S07PermissionSystem.java # 自包含（新增）
│   │       ├── S08HookSystem.java       # 自包含（新增）
│   │       ├── S09MemorySystem.java     # 自包含（新增）
│   │       ├── S10SystemPrompt.java     # 自包含（新增）
│   │       ├── S11ErrorRecovery.java    # 自包含（新增）
│   │       ├── S12TaskSystem.java       # 自包含（重编号）
│   │       ├── S13BackgroundTasks.java  # 自包含（重编号）
│   │       ├── S14CronScheduler.java    # 自包含（新增）
│   │       ├── S15AgentTeams.java       # 自包含（重编号）
│   │       ├── S16TeamProtocols.java    # 自包含（重编号）
│   │       ├── S17AutonomousAgents.java # 自包含（重编号）
│   │       ├── S18WorktreeIsolation.java# 自包含（重编号）
│   │       ├── S19McpPlugin.java        # 自包含（新增）
│   │       └── SFullAgent.java          # 自包含（整合 s01-s18）
│   └── resources/
│       └── (空，删除 logback.xml)
├── docs/
│   ├── en/                              # 英文文档（s01-s19）
│   └── cn/                              # 中文文档（s01-s19）
└── target/                              # 构建输出
```

**已删除的目录**：core/、tools/、util/、tasks/、skills/（旧）、compress/、background/、team/、worktree/
