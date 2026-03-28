# mini-agent-4j

> **learn-claude-code** 的 Java 21 完整重写 —— 用 12 节渐进式课程，从零构建一个 AI 编码 Agent。

本项目将 [learn-claude-code](https://github.com/anthropics/learn-claude-code) 的 13 个 Python Agent（s01–s12 + s_full.py）用 Java 21 重写，保持原项目的渐进式教学结构和 **Harness Engineering** 理念。

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

- **34 个 Java 源文件**，约 4600 行代码
- **Java 21 现代特性**：Virtual Threads、Record、Switch Expression、Pattern Matching
- **13 节渐进式课程**：每课只增加一个新概念，独立可运行
- **最小依赖**：仅 anthropic-java SDK + Jackson + dotenv-java，不使用 Spring Boot
- **中文注释**：所有代码使用中文 Javadoc 注释

## 课程大纲

| 课程 | 主题 | 新增概念 | 运行命令 |
|------|------|----------|----------|
| S01 | Agent Loop | while 循环 + bash 工具 | `mvn exec:java` / `java -jar mini-agent-4j.jar s01` |
| S02 | Tool Use | 工具分发表 (dispatch map) | `... -Dexec.mainClass=...S02ToolUse` / `... s02` |
| S03 | TodoWrite | 结构化计划跟踪 + nag reminder | `... S03TodoWrite` / `... s03` |
| S04 | Subagent | 上下文隔离（独立 messages=[]） | `... S04Subagent` / `... s04` |
| S05 | Skill Loading | 两层技能注入（元数据 + 按需加载） | `... S05SkillLoading` / `... s05` |
| S06 | Context Compact | 三层压缩管线（micro/auto/manual） | `... S06ContextCompact` / `... s06` |
| S07 | Task System | 文件持久化 DAG 任务系统 | `... S07TaskSystem` / `... s07` |
| S08 | Background Tasks | Virtual Thread 后台执行 + 通知队列 | `... S08BackgroundTasks` / `... s08` |
| S09 | Agent Teams | JSONL 邮箱 + 持久化 Teammate | `... S09AgentTeams` / `... s09` |
| S10 | Team Protocols | Shutdown / Plan Approval 握手协议 | `... S10TeamProtocols` / `... s10` |
| S11 | Autonomous Agents | 空闲轮询 + 自动认领任务 | `... S11AutonomousAgents` / `... s11` |
| S12 | Worktree Isolation | Git Worktree 目录级隔离 | `... S12WorktreeIsolation` / `... s12` |
| SFull | Full Agent | 22 个工具全量整合 | `... SFullAgent` / `... full` |

> 表中左列为 `mvn exec:java` 方式（需在项目目录下），右列为 `java -jar` 方式（打包后可在任意目录运行）。

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

### 打包为可执行 jar（可在任意目录运行）

```bash
# 打包 fat jar（包含所有依赖）
mvn clean package

# 在任意目录运行，通过 session 名称指定课程
java -jar target/mini-agent-4j-1.0-SNAPSHOT.jar s03          # S03TodoWrite
java -jar target/mini-agent-4j-1.0-SNAPSHOT.jar s01          # S01AgentLoop
java -jar target/mini-agent-4j-1.0-SNAPSHOT.jar full         # SFullAgent

# 也可将 jar 复制到任意位置，在目标工作目录下运行
cp target/mini-agent-4j-1.0-SNAPSHOT.jar ~/my-project/
cd ~/my-project/
java -jar mini-agent-4j-1.0-SNAPSHOT.jar s03
```

> `.env` 文件从运行时的工作目录向上查找；也可通过系统环境变量设置 `ANTHROPIC_API_KEY` 和 `MODEL_ID`。

### 环境变量

| 变量名 | 必需 | 说明 |
|--------|------|------|
| `ANTHROPIC_API_KEY` | 是 | Anthropic API 密钥 |
| `MODEL_ID` | 是 | 模型 ID，如 `claude-sonnet-4-20250514` |
| `ANTHROPIC_BASE_URL` | 否 | 自定义 API 端点（兼容第三方服务） |

## 项目结构

```
mini-agent-4j/
├── pom.xml                          # Maven 配置（Java 21 + 5 个依赖）
├── .env.example                     # 环境变量模板
└── src/main/
    ├── java/com/example/agent/
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
    │   │   ├── EnvLoader.java       #   dotenv-java 封装
    │   │   ├── PathSandbox.java     #   路径穿越防护
    │   │   └── TokenEstimator.java  #   粗略 token 估算
    │   ├── tasks/                   # 任务管理
    │   │   ├── TodoManager.java     #   内存 Todo 列表（s03）
    │   │   ├── TaskManager.java     #   文件持久化 DAG（s07）
    │   │   └── Task.java            #   record 数据载体
    │   ├── skills/                  # 技能系统
    │   │   └── SkillLoader.java     #   两层注入（s05）
    │   ├── compress/                # 上下文压缩
    │   │   └── ContextCompressor.java # 三层管线（s06）
    │   ├── background/              # 后台执行
    │   │   └── BackgroundManager.java # Virtual Thread + Queue（s08）
    │   ├── team/                    # 多 Agent 团队
    │   │   ├── MessageBus.java      #   JSONL 邮箱（s09）
    │   │   ├── TeamManager.java     #   团队生命周期（s09）
    │   │   ├── TeamProtocol.java    #   握手协议（s10）
    │   │   └── Teammate.java        #   record 标识
    │   ├── worktree/                # Git Worktree 隔离
    │   │   └── WorktreeManager.java #   create/run/remove（s12）
    │   ├── Launcher.java            #   统一启动入口（fat jar 入口）
    │   └── sessions/                # 13 节课程（独立可运行）
    │       ├── S01AgentLoop.java    #   最小循环
    │       ├── S02ToolUse.java      #   工具分发
    │       ├── ...
    │       ├── S12WorktreeIsolation.java
    │       └── SFullAgent.java      #   全量整合（22 个工具）
    └── resources/
        └── logback.xml              # 日志配置
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
            String output = dispatcher.dispatch(name, input); // 执行工具
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
S07  + file-based task DAG          ← 持久化
S08  + Virtual Thread background    ← 并发执行
S09  + JSONL inbox + teammates      ← 团队协作
S10  + shutdown/plan protocols      ← 结构化握手
S11  + idle polling + auto-claim    ← 自治 Agent
S12  + git worktree isolation       ← 目录隔离
```

## Java 21 特性使用

| 特性 | 场景 | 示例 |
|------|------|------|
| Virtual Thread | 后台任务、Teammate 线程 | `Thread.ofVirtual().name("agent-alice").start(...)` |
| Record | 不可变数据载体 | `record Task(int id, String subject, ...)` |
| Switch Expression | 状态标记渲染 | `case "pending" -> "[ ]"` |
| Pattern Matching | 类型安全转换 | `if (obj instanceof Number n) n.intValue()` |
| `var` | 局部类型推断 | `var dispatcher = new ToolDispatcher()` |

## 与 Python 原版对照

| Python 概念 | Java 实现 |
|-------------|-----------|
| `client = Anthropic()` | `AnthropicOkHttpClient.builder().apiKey(...).build()` |
| `response.stop_reason == "tool_use"` | `StopReason.TOOL_USE.equals(response.stopReason())` |
| `block.type == "tool_use"` | `block.isToolUse()` → `block.asToolUse()` |
| `TOOL_HANDLERS = {"bash": lambda ...}` | `dispatcher.register("bash", input -> ...)` |
| `threading.Thread(target=..., daemon=True)` | `Thread.ofVirtual().name(...).start(...)` |
| `load_dotenv(override=True)` | `EnvLoader` (dotenv-java 封装) |
| `path.is_relative_to(WORKDIR)` | `PathSandbox.safePath()` (normalize + startsWith) |
| `TOOL_HANDLERS` dict | `ToolDispatcher` (Map + 异常统一处理) |

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
.tasks/          # 持久化任务 JSON 文件（s07+）
.team/           # 团队配置 + JSONL 收件箱（s09+）
.transcripts/    # 上下文压缩转录文件（s06+）
.worktrees/      # Git Worktree 索引 + 事件日志（s12）
```

## 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| [anthropic-java](https://github.com/anthropics/anthropic-sdk-java) | 1.2.0 | Claude API 通信 |
| [jackson-databind](https://github.com/FasterXML/jackson) | 2.17.2 | JSON 序列化 |
| [dotenv-java](https://github.com/cdimascio/dotenv-java) | 3.0.2 | .env 文件加载 |
| [picocli](https://picocli.info/) | 4.7.6 | CLI 参数解析（预留） |
| [logback-classic](https://logback.qos.ch/) | 1.5.6 | SLF4J 日志实现 |

## 许可证

本项目仅供学习用途。原始 Python 版本来自 [Anthropic learn-claude-code](https://github.com/anthropics/learn-claude-code)。
