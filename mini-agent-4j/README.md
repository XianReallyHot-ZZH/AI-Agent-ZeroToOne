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

- **20 个自包含 Session 文件**，共 ~19000 行代码，每个零外部依赖、独立可运行
- **19 节渐进式课程 + 1 个全量整合**，编号与 Python 版完全对齐（s01–s19 + full）
- **Java 21 现代特性**：Virtual Threads、Switch Expression、Pattern Matching、Sealed Classes
- **完全自包含**：每个 Session 文件不导入 `com.example.agent.*` 中的任何其他类
- **最小依赖**：仅 anthropic-java SDK + Jackson + dotenv-java + logback
- **中文注释**：所有代码使用中文 Javadoc 和行内注释
- **双语文档**：每个 Session 配套中/英文教学文档（共 38 篇）
- **跨平台**：自动检测 OS，Windows 用 `cmd /c`，Unix 用 `bash -c`

## 课程大纲

每课在前一课基础上只增加一个新概念，所有 Session 的 Agent 循环核心结构完全相同：

```java
while (true) {
    Message response = client.messages().create(params);
    paramsBuilder.addMessage(response);
    if (response.stopReason() != TOOL_USE) return;  // 模型停止说话
    for (var block : response.content()) {
        if (block.isToolUse()) {
            String output = dispatch.get(name).apply(input);  // 执行工具
            results.add(toolResult(id, output));
        }
    }
    paramsBuilder.addUserMessageOfBlockParams(results);  // 回传结果
}
```

| 课程 | Session 类 | 主题 | 新增概念 | 工具数 | 代码行 |
|------|-----------|------|----------|--------|--------|
| S01 | `S01AgentLoop` | Agent Loop | while 循环 + bash 工具 | 3 | 519 |
| S02 | `S02ToolUse` | Tool Use | 工具分发表 + read/write/edit | 7 | 685 |
| S03 | `S03TodoWrite` | TodoWrite | 结构化计划跟踪 + nag reminder | 7 | 970 |
| S04 | `S04Subagent` | Subagent | 上下文隔离（独立 messages=[]） | 7 | 819 |
| S05 | `S05SkillLoading` | Skill Loading | 两层技能注入（元数据 + 按需加载） | 7 | 736 |
| S06 | `S06ContextCompact` | Context Compact | 三层压缩管线（micro/auto/manual） | 6 | 1011 |
| S07 | `S07PermissionSystem` | Permission System | 权限管线（deny→mode→allow→ask） | 7 | 1134 |
| S08 | `S08HookSystem` | Hook System | 钩子回调（PreToolUse/PostToolUse） | 7 | 1288 |
| S09 | `S09MemorySystem` | Memory System | 持久化记忆 + frontmatter 存储 | 7 | 1389 |
| S10 | `S10SystemPrompt` | System Prompt | 6 段系统提示词组装管线 | 7 | 1172 |
| S11 | `S11ErrorRecovery` | Error Recovery | 三种恢复策略 + 指数退避重试 | 5 | 970 |
| S12 | `S12TaskSystem` | Task System | 文件持久化 DAG 任务系统 | 9 | 457 |
| S13 | `S13BackgroundTasks` | Background Tasks | Virtual Thread 后台执行 + 通知队列 | 7 | 354 |
| S14 | `S14CronScheduler` | Cron Scheduler | Cron 定时任务 + 后台轮询线程 | 9 | 1376 |
| S15 | `S15AgentTeams` | Agent Teams | JSONL 邮箱 + 持久化 Teammate | 17 | 1394 |
| S16 | `S16TeamProtocols` | Team Protocols | Shutdown / Plan Approval 握手协议 | 22 | 1455 |
| S17 | `S17AutonomousAgents` | Autonomous Agents | idle 轮询 + 自动认领任务 | 25 | 544 |
| S18 | `S18WorktreeIsolation` | Worktree Isolation | Git Worktree 目录级隔离 | 17 | 454 |
| S19 | `S19McpPlugin` | MCP Plugin | JSON-RPC 2.0 外部工具集成 | 6 | 1540 |
| SFull | `SFullAgent` | Full Agent | 23 个工具全量整合 | 23 | 742 |

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
mvn exec:java -Dexec.mainClass="com.example.agent.sessions.S03TodoWrite"
```

### 打包为可执行 jar

```bash
mvn clean package

# 通过 Launcher 统一入口运行，用 session 名称指定课程
java -jar target/mini-agent-4j-1.0-SNAPSHOT.jar s01    # S01AgentLoop
java -jar target/mini-agent-4j-1.0-SNAPSHOT.jar s07    # S07PermissionSystem
java -jar target/mini-agent-4j-1.0-SNAPSHOT.jar s12    # S12TaskSystem
java -jar target/mini-agent-4j-1.0-SNAPSHOT.jar full   # SFullAgent (23 tools)

# 列出所有可用 session
java -jar target/mini-agent-4j-1.0-SNAPSHOT.jar help

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
├── pom.xml                          # Maven 配置（Java 21, 4 个依赖）
├── .env.example                     # 环境变量模板
├── skills/                          # 技能文件（SKILL.md frontmatter）
│   ├── agent-builder/               #   Agent 构建指南
│   ├── code-review/                 #   代码审查技能
│   ├── mcp-builder/                 #   MCP 服务器构建指南
│   └── pdf/                         #   PDF 处理技能
├── docs/                            # 教学文档（中英双语）
│   ├── cn/                          #   中文文档 (19 篇)
│   └── en/                          #   English docs (19)
└── src/main/java/com/example/agent/
    ├── Launcher.java                # 统一启动入口 (s01-s19 + full)
    └── sessions/                    # 20 个自包含 Session
        ├── S01AgentLoop.java        # ...519 行 | 最小循环
        ├── S02ToolUse.java          # ...685 行 | 工具分发
        ├── S03TodoWrite.java        # ...970 行 | 计划跟踪
        ├── S04Subagent.java         # ...819 行 | 上下文隔离
        ├── S05SkillLoading.java     # ...736 行 | 技能注入
        ├── S06ContextCompact.java   # .1011 行 | 上下文压缩
        ├── S07PermissionSystem.java # .1134 行 | 权限管线
        ├── S08HookSystem.java       # .1288 行 | 钩子系统
        ├── S09MemorySystem.java     # .1389 行 | 持久化记忆
        ├── S10SystemPrompt.java     # .1172 行 | 提示词组装
        ├── S11ErrorRecovery.java    # ...970 行 | 错误恢复
        ├── S12TaskSystem.java       # ...457 行 | 文件持久化任务
        ├── S13BackgroundTasks.java  # ...354 行 | 后台执行
        ├── S14CronScheduler.java    # .1376 行 | Cron 定时
        ├── S15AgentTeams.java       # .1394 行 | 团队协作
        ├── S16TeamProtocols.java    # .1455 行 | 握手协议
        ├── S17AutonomousAgents.java # ...544 行 | 自治 Agent
        ├── S18WorktreeIsolation.java# ...454 行 | 目录隔离
        ├── S19McpPlugin.java        # .1540 行 | MCP 集成
        └── SFullAgent.java          # ...742 行 | 全量整合 (23 工具)
```

## 渐进式概念图

每课只在循环上增加一个"装具"，循环本身从不改变：

```
S01  while loop + bash              ← 核心模式
S02  + dispatch map (4 tools)       ← 工具路由
S03  + TodoManager + nag            ← 计划跟踪
S04  + subagent (fresh context)     ← 上下文隔离
S05  + skill loader (2-layer)       ← 按需知识
S06  + 3-layer compression          ← 无限工作
S07  + permission pipeline          ← 安全防护 (deny→mode→allow→ask)
S08  + hook system                  ← 可插拔回调 (PreToolUse/PostToolUse)
S09  + persistent memory            ← 跨会话记忆 (frontmatter + MEMORY.md)
S10  + system prompt assembly       ← 提示词工程 (6 sections + dynamic boundary)
S11  + error recovery               ← 弹性恢复 (max_tokens/compact/backoff)
S12  + file-based task DAG          ← 持久化任务 (.tasks/*.json)
S13  + Virtual Thread background    ← 并发执行 (drain notification queue)
S14  + cron scheduler               ← 定时任务 (5-field cron + auto-expiry)
S15  + JSONL inbox + teammates      ← 团队协作 (spawn_teammate + message bus)
S16  + shutdown/plan protocols      ← 结构化握手 (ConcurrentHashMap state)
S17  + idle polling + auto-claim    ← 自治 Agent (work→idle→work lifecycle)
S18  + git worktree isolation       ← 目录隔离 (control plane + execution plane)
S19  + MCP external tools           ← 外部工具 (JSON-RPC 2.0 over stdio)
```

## Java 21 特性使用

| 特性 | 场景 | 示例 |
|------|------|------|
| Virtual Thread | 后台任务、Teammate 线程、Cron 轮询 | `Thread.ofVirtual().name("agent-alice").start(...)` |
| Switch Expression | 状态标记渲染 | `case "pending" -> "[ ]"; case "in_progress" -> "[>]";` |
| Pattern Matching | 类型安全转换 | `if (input.get("limit") instanceof Number n) n.intValue()` |
| `var` | 局部类型推断 | `var dispatch = new LinkedHashMap<String, Function<...>>();` |
| Text Block | 多行字符串 | `"""Summarize this conversation..."""` |
| Sealed Classes | JsonValue 分支处理 | `value.asString()` / `value.asNumber()` / `value.asObject()` |

## 与 Python 原版对照

| Python 概念 | Java 实现 |
|-------------|-----------|
| `client = Anthropic()` | `AnthropicOkHttpClient.builder().apiKey(key).build()` |
| `response.stop_reason == "tool_use"` | `response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)` |
| `block.type == "tool_use"` | `block.isToolUse()` → `block.asToolUse()` |
| `TOOL_HANDLERS = {"bash": lambda ...}` | `dispatch.put("bash", input -> runBash(...))` |
| `threading.Thread(target=..., daemon=True)` | `Thread.ofVirtual().name("bg").start(...)` |
| `load_dotenv(override=True)` | `Dotenv.configure().directory(...).ignoreIfMissing().load()` |
| `path.is_relative_to(WORKDIR)` | `resolve().normalize().startsWith(WORKDIR)` |
| `json.dumps(msg)` / `json.loads(line)` | `MAPPER.writeValueAsString(msg)` / `MAPPER.readValue(line, Map.class)` |
| `queue.Queue()` | `LinkedBlockingQueue<Map<String,String>>()` |
| `subprocess.run(["bash","-c",cmd])` | `new ProcessBuilder("bash","-c",cmd).start()` |
| `f"Task #{id}: {subject}"` | `"Task #"+id+": "+subject` 或 `STR."Task #\{id}: \{subject}"` |

## SFullAgent（全量整合）

SFullAgent 是所有 s01-s18 机制的完整集成，包含 23 个工具：

| 工具类别 | 工具名 | 说明 |
|----------|--------|------|
| 基础 | `bash`, `read_file`, `write_file`, `edit_file` | 文件和命令操作 |
| 计划 | `TodoWrite` | 结构化清单跟踪 |
| 委派 | `task` | Subagent 上下文隔离 |
| 知识 | `load_skill` | 按需技能加载 |
| 压缩 | `compress` | 手动上下文压缩 |
| 后台 | `background_run`, `check_background` | Virtual Thread 异步执行 |
| 任务 | `task_create`, `task_get`, `task_update`, `task_list` | 文件持久化 DAG 任务板 |
| 团队 | `spawn_teammate`, `list_teammates` | Teammate 生命周期管理 |
| 通信 | `send_message`, `read_inbox`, `broadcast` | JSONL 消息总线 |
| 协议 | `shutdown_request`, `plan_approval` | 结构化握手协议 |
| 自治 | `idle`, `claim_task` | idle 轮询 + 任务认领 |

REPL 斜杠命令：`/compact` | `/tasks` | `/team` | `/inbox`

## 运行时产物

Agent 运行时会在工作目录下生成以下文件（已在 `.gitignore` 中排除）：

```
.tasks/          # 持久化任务 JSON（task_N.json）        s12+
.team/           # 团队配置 + JSONL 收件箱                s15+
.transcripts/    # 上下文压缩转录文件                     s06+
.worktrees/      # Git Worktree 索引 + 事件日志          s18
.claude-plugin/  # MCP 插件清单                          s19
```

## 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| [anthropic-java](https://github.com/anthropics/anthropic-sdk-java) | 1.2.0 | Claude API 通信（OkHttp 实现） |
| [jackson-databind](https://github.com/FasterXML/jackson) | 2.17.2 | JSON 序列化/反序列化 |
| [dotenv-java](https://github.com/cdimascio/dotenv-java) | 3.0.2 | .env 文件环境变量加载 |
| [logback-classic](https://logback.qos.ch/) | 1.5.6 | SLF4J 日志实现（抑制 SDK 噪音） |

## 许可证

本项目仅供学习用途。原始 Python 版本来自 [Anthropic learn-claude-code](https://github.com/anthropics/learn-claude-code)。
