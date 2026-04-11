# mini-agent-4j

> **learn-claude-code** 的 Java 21 完整重写 —— 用 19 节渐进式课程，从零构建一个 AI 编码 Agent。

本项目将 [learn-claude-code](https://github.com/anthropics/learn-claude-code) 的 Python Agent（s01–s19 + s_full.py）用 Java 21 重写，保持原项目的渐进式教学结构和 **Harness Engineering** 理念。

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

## 特性

- **20 个自包含 Session 文件**，每个零外部依赖、独立可运行
- **Java 21 现代特性**：Virtual Threads、Record、Switch Expression、Pattern Matching
- **19 节渐进式课程**：每课只增加一个新概念，编号与 Python 版完全对齐
- **最小依赖**：仅 anthropic-java SDK + Jackson + dotenv-java + logback
- **中文注释**：所有代码使用中文 Javadoc 注释
- **完全自包含**：每个 Session 文件不依赖 `com.example.agent.*` 中的其他类

## 课程大纲

| 课程 | 主题 | 新增概念 | 运行命令 |
|------|------|----------|----------|
| S01 | Agent Loop | while 循环 + bash 工具 | `java -jar mini-agent-4j.jar s01` |
| S02 | Tool Use | 工具分发表 (dispatch map) | `... s02` |
| S03 | TodoWrite | 结构化计划跟踪 + nag reminder | `... s03` |
| S04 | Subagent | 上下文隔离（独立 messages=[]） | `... s04` |
| S05 | Skill Loading | 两层技能注入（元数据 + 按需加载） | `... s05` |
| S06 | Context Compact | 三层压缩管线（micro/auto/manual） | `... s06` |
| S07 | Permission System | 权限管线（deny→mode→allow→ask） | `... s07` |
| S08 | Hook System | 钩子系统（PreToolUse/PostToolUse） | `... s08` |
| S09 | Memory System | 持久化记忆 + MEMORY.md 索引 | `... s09` |
| S10 | System Prompt | 6 段系统提示词组装管线 | `... s10` |
| S11 | Error Recovery | 三种恢复策略 + 指数退避 | `... s11` |
| S12 | Task System | 文件持久化 DAG 任务系统 | `... s12` |
| S13 | Background Tasks | Virtual Thread 后台执行 + 通知队列 | `... s13` |
| S14 | Cron Scheduler | Cron 定时任务 + 后台线程 | `... s14` |
| S15 | Agent Teams | JSONL 邮箱 + 持久化 Teammate | `... s15` |
| S16 | Team Protocols | Shutdown / Plan Approval 握手协议 | `... s16` |
| S17 | Autonomous Agents | 空闲轮询 + 自动认领任务 | `... s17` |
| S18 | Worktree Isolation | Git Worktree 目录级隔离 | `... s18` |
| S19 | MCP Plugin | JSON-RPC 外部工具集成 | `... s19` |
| SFull | Full Agent | 23 个工具全量整合 | `... full` |

## 快速开始

### 前置条件

- **Java 21+**（需要 Virtual Threads 支持）
- **Maven 3.9+**
- **Anthropic API Key**（或兼容端点的 Key）

### 安装与运行

```bash
# 1. 进入项目目录
cd mini-agent-4j

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env，填入你的 API Key 和模型 ID

# 3. 编译
mvn compile

# 4. 运行任意课程（以 S03 为例）
mvn exec:java
# 或直接指定 session：
mvn exec:java -Dexec.mainClass="com.example.agent.sessions.S03TodoWrite"
```

### 打包为可执行 jar

```bash
# 打包 fat jar（包含所有依赖）
mvn clean package

# 在任意目录运行
java -jar target/mini-agent-4j-1.0-SNAPSHOT.jar s03    # S03TodoWrite
java -jar target/mini-agent-4j-1.0-SNAPSHOT.jar full   # SFullAgent

# 复制到任意工作目录运行
cp target/mini-agent-4j-1.0-SNAPSHOT.jar ~/my-project/
cd ~/my-project/
java -jar mini-agent-4j-1.0-SNAPSHOT.jar s03
```

### 环境变量

| 变量名 | 必需 | 说明 |
|--------|------|------|
| `ANTHROPIC_API_KEY` | 是 | Anthropic API 密钥 |
| `MODEL_ID` | 是 | 模型 ID，如 `claude-sonnet-4-20250514` |
| `ANTHROPIC_BASE_URL` | 否 | 自定义 API 端点（兼容第三方服务） |

## 项目结构

```
mini-agent-4j/
├── pom.xml                          # Maven 配置（Java 21 + 4 个依赖）
├── .env.example                     # 环境变量模板
├── skills/                          # 技能文件（SKILL.md）
│   ├── agent-builder/
│   ├── code-review/
│   ├── mcp-builder/
│   └── pdf/
└── src/main/java/com/example/agent/
    ├── Launcher.java                # 统一启动入口（fat jar 入口）
    └── sessions/                    # 20 节课程（全部自包含）
        ├── S01AgentLoop.java        # 最小循环
        ├── S02ToolUse.java          # 工具分发
        ├── S03TodoWrite.java        # 结构化计划跟踪
        ├── S04Subagent.java         # 上下文隔离
        ├── S05SkillLoading.java     # 两层技能注入
        ├── S06ContextCompact.java   # 三层压缩管线
        ├── S07PermissionSystem.java # 权限管线
        ├── S08HookSystem.java       # 钩子系统
        ├── S09MemorySystem.java     # 持久化记忆
        ├── S10SystemPrompt.java     # 系统提示词组装
        ├── S11ErrorRecovery.java    # 错误恢复
        ├── S12TaskSystem.java       # 文件持久化 DAG 任务
        ├── S13BackgroundTasks.java  # Virtual Thread 后台执行
        ├── S14CronScheduler.java    # Cron 定时任务
        ├── S15AgentTeams.java       # JSONL 邮箱 + Teammate
        ├── S16TeamProtocols.java    # 握手协议
        ├── S17AutonomousAgents.java # 自治 Agent
        ├── S18WorktreeIsolation.java# Git Worktree 隔离
        ├── S19McpPlugin.java        # MCP 外部工具集成
        └── SFullAgent.java          # 全量整合（23 个工具）
```

## 核心架构

### Agent 循环（不变的核心）

```java
// 整个项目的秘密浓缩在这个 while 循环中
while (true) {
    Message response = client.messages().create(params);   // 调用 LLM
    paramsBuilder.addMessage(response);                     // 追加回复

    if (!StopReason.TOOL_USE.equals(response.stopReason()))
        return;                                             // 模型停止

    for (ContentBlock block : response.content()) {
        if (block.isToolUse()) {
            String output = dispatch.get(name).apply(input); // 执行工具
            results.add(toolResult(id, output));              // 收集结果
        }
    }
    paramsBuilder.addUserMessageOfBlockParams(results);     // 回传结果
}
```

### 渐进式扩展

每课只在循环上增加一个"装具"，循环本身从不改变：

```
S01  while loop + bash              ← 核心模式
S02  + dispatch map                 ← 工具路由
S03  + TodoManager + nag            ← 计划跟踪
S04  + subagent (fresh context)     ← 上下文隔离
S05  + skill loader (2-layer)       ← 按需知识
S06  + 3-layer compression          ← 无限工作
S07  + permission pipeline          ← 安全防护
S08  + hook system                  ← 可插拔回调
S09  + persistent memory            ← 跨会话记忆
S10  + system prompt assembly       ← 提示词工程
S11  + error recovery               ← 弹性恢复
S12  + file-based task DAG          ← 持久化任务
S13  + Virtual Thread background    ← 并发执行
S14  + cron scheduler               ← 定时任务
S15  + JSONL inbox + teammates      ← 团队协作
S16  + shutdown/plan protocols      ← 结构化握手
S17  + idle polling + auto-claim    ← 自治 Agent
S18  + git worktree isolation       ← 目录隔离
S19  + MCP external tools           ← 外部工具集成
```

## Java 21 特性使用

| 特性 | 场景 | 示例 |
|------|------|------|
| Virtual Thread | 后台任务、Teammate 线程 | `Thread.ofVirtual().name("agent-alice").start(...)` |
| Switch Expression | 状态标记渲染 | `case "pending" -> "[ ]"` |
| Pattern Matching | 类型安全转换 | `if (obj instanceof Number n) n.intValue()` |
| `var` | 局部类型推断 | `var dispatcher = new LinkedHashMap<>()` |

## 与 Python 原版对照

| Python 概念 | Java 实现 |
|-------------|-----------|
| `client = Anthropic()` | `AnthropicOkHttpClient.builder().apiKey(...).build()` |
| `response.stop_reason == "tool_use"` | `StopReason.TOOL_USE.equals(response.stopReason())` |
| `block.type == "tool_use"` | `block.isToolUse()` → `block.asToolUse()` |
| `TOOL_HANDLERS = {"bash": lambda ...}` | `dispatch.put("bash", input -> ...)` |
| `threading.Thread(target=..., daemon=True)` | `Thread.ofVirtual().name(...).start(...)` |
| `load_dotenv(override=True)` | `Dotenv.configure().directory(...).load()` |
| `path.is_relative_to(WORKDIR)` | `resolve().normalize().startsWith(WORKDIR)` |

## SFullAgent REPL 命令

全量 Agent 启动后支持以下斜杠命令：

| 命令 | 功能 |
|------|------|
| `/compact` | 手动触发上下文压缩 |
| `/tasks` | 列出所有持久化任务 |
| `/team` | 列出所有 Teammate 状态 |
| `/inbox` | 读取并清空 Lead 收件箱 |

## 运行时产物

Agent 运行时会在工作目录下生成以下文件（已在 `.gitignore` 中排除）：

```
.tasks/          # 持久化任务 JSON 文件（s12+）
.team/           # 团队配置 + JSONL 收件箱（s15+）
.transcripts/    # 上下文压缩转录文件（s06+）
.worktrees/      # Git Worktree 索引 + 事件日志（s18）
.claude-plugin/  # MCP 插件清单（s19）
```

## 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| [anthropic-java](https://github.com/anthropics/anthropic-sdk-java) | 1.2.0 | Claude API 通信 |
| [jackson-databind](https://github.com/FasterXML/jackson) | 2.17.2 | JSON 序列化 |
| [dotenv-java](https://github.com/cdimascio/dotenv-java) | 3.0.2 | .env 文件加载 |
| [logback-classic](https://logback.qos.ch/) | 1.5.6 | SLF4J 日志实现 |

## 许可证

本项目仅供学习用途。原始 Python 版本来自 [Anthropic learn-claude-code](https://github.com/anthropics/learn-claude-code)。
