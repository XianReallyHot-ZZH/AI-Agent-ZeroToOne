# SFull: Full Agent (全量参考实现)

`s00 > s01 > s02 > s03 > s04 > s05 > s06 > s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19 > [ SFull ]`

> *不是新概念，而是所有概念的汇合。*

## 这一章到底在讲什么

前面 19 章，每一章只引入一个新机制。

到了 SFull，所有机制被放进同一个文件、同一个主循环、同一组分发器里。

这不是"又加一层"，而是：

> **把 s01 到 s18 的每一条线都接回同一个执行面。**

如果你已经理解了前面每一章独立做了什么，那 SFull 回答的就是：

**当它们同时存在时，代码长什么样。**

## 最小心智模型

```text
用户输入
  |
  v
REPL 循环
  |
  v
fullAgentLoop
  |
  +-- 1. Microcompact（s06：保留最近 3 个 tool_result，压缩旧的）
  +-- 2. Auto-compact（s06：token 估算超阈值 → LLM 生成摘要 → 替换历史）
  +-- 3. Drain 后台通知（s13：BackgroundManager 的完成结果注入对话）
  +-- 4. Drain lead 收件箱（s15：teammate 发来的消息注入对话）
  +-- 5. Rebuild paramsBuilder from history
  +-- 6. LLM 调用
  +-- 7. 工具执行（23 个工具，按来源分组）
  +-- 8. Nag reminder（s03：连续 3 轮未更新 todo → 插入提醒）
  +-- 9. Manual compress（s06：如果 LLM 调用了 compress → 触发压缩）
  |
  v
回到循环顶部
```

## 为什么放在最后

因为 SFull 不引入新概念。

它的教学价值在于：

- 让你看到所有机制同时运行时的代码结构
- 让你理解"多个机制共享同一个循环"时，执行顺序为什么重要
- 让你有一个可以直接运行的全量参考

如果你还没真正理解 agent loop、tool call、permission、task、worktree、MCP 中的任何一个，先回去看对应章节。

SFull 只是帮你把它们一起看一遍。

## 23 个工具按来源分组

### 基础工具（s02）

| # | 工具 | 说明 |
|---|------|------|
| 1 | bash | 执行 shell 命令（OS 自适应、危险命令拦截、超时） |
| 2 | read_file | 读取文件内容（路径沙箱、可选行数限制） |
| 3 | write_file | 写入文件（自动创建父目录、路径沙箱） |
| 4 | edit_file | 精确文本替换（首次匹配） |

### 待办清单（s03）

| # | 工具 | 说明 |
|---|------|------|
| 5 | TodoWrite | 更新待办列表（最多 20 项、仅 1 个 in_progress） |

### 子代理（s04）

| # | 工具 | 说明 |
|---|------|------|
| 6 | task | 派生子代理执行探索或通用任务（最多 30 轮） |

### 技能系统（s05）

| # | 工具 | 说明 |
|---|------|------|
| 7 | load_skill | 从 skills/ 目录加载技能文件 |

### 压缩（s06）

| # | 工具 | 说明 |
|---|------|------|
| 8 | compress | 手动触发对话压缩（可选 focus 参数） |

### 后台任务（s13）

| # | 工具 | 说明 |
|---|------|------|
| 9 | background_run | 后台执行命令，立即返回 task_id |
| 10 | check_background | 检查后台任务状态 |

### 持久化任务板（s12）

| # | 工具 | 说明 |
|---|------|------|
| 11 | task_create | 创建持久化任务（.tasks/task_N.json） |
| 12 | task_get | 获取任务详情 |
| 13 | task_update | 更新任务状态/依赖（含双向 DAG 维护） |
| 14 | task_list | 列出所有任务（含 blockedBy 信息） |

### 团队协作（s15/s16/s17）

| # | 工具 | 说明 |
|---|------|------|
| 15 | spawn_teammate | 生成持久化自治 Teammate（虚拟线程） |
| 16 | list_teammates | 列出所有 Teammate 及状态 |
| 17 | send_message | 发送消息给 Teammate（含 VALID_MSG_TYPES 验证） |
| 18 | read_inbox | 读取并清空 lead 收件箱 |
| 19 | broadcast | 广播消息给所有 Teammate |
| 20 | shutdown_request | 请求 Teammate 关闭（s16 协议） |
| 21 | plan_approval | 审批/拒绝 Teammate 的计划（s16 协议） |
| 22 | idle | Teammate 声明无更多工作（s17） |
| 23 | claim_task | 从任务板认领任务（s17） |

## 关键数据结构

### 1. 消息历史（messageHistory）

SFull 使用独立的消息历史列表来支持 microcompact 和 auto-compact：

```java
List<Object> messageHistory = new ArrayList<>();
// 每个元素为以下类型之一：
//   String               → user text 或 ack marker（"__ack_bg__"/"__ack_inbox__"）
//   Message              → assistant 响应
//   Map<"blocks", List<ContentBlockParam>,
//       "tools", List<String>,
//       "ids", List<String>>  → tool result turn
```

每轮 LLM 调用前，从 history 重建 `paramsBuilder`。这使得 microcompact 可以直接修改历史中的旧 tool result。

### 2. 工具结果 Turn 信息

```java
var turnInfo = new LinkedHashMap<>();
turnInfo.put("blocks", results);        // ContentBlockParam 列表
turnInfo.put("tools", resultToolNames); // 工具名列表（用于 microcompact 判断）
turnInfo.put("ids", resultToolUseIds);  // toolUseId 列表（用于重建 tool_result）
messageHistory.add(turnInfo);
```

### 3. 内部模块实例

```java
TodoManager todo;       // s03：内存态待办清单
SkillRegistry skills;   // s05：两层技能加载
BackgroundManager bg;   // s13：Virtual Thread 后台执行
MessageBus bus;         // s15：JSONL 邮箱式团队通信
```

### 4. 协议状态

```java
ConcurrentHashMap<String, Map<String, Object>> shutdownRequests;  // s16
ConcurrentHashMap<String, Map<String, Object>> planRequests;      // s16
```

## 核心流程：每轮 LLM 调用前后的管道

### 调用前（4 步预处理）

```java
// 1. Microcompact：压缩旧的 tool_result
microcompact(messageHistory);

// 2. Auto-compact：token 估算超阈值 → 替换历史为摘要
if (tokenEstimate > TOKEN_THRESHOLD) doAutoCompact(messageHistory, null);

// 3. Drain 后台通知 → 注入对话
var notifs = bg.drain();
// → messageHistory.add(txt); messageHistory.add("__ack_bg__");

// 4. Drain lead 收件箱 → 注入对话
var inbox = bus.readInbox("lead");
// → messageHistory.add(...); messageHistory.add("__ack_inbox__");
```

### 调用后（2 步后处理）

```java
// 1. Nag reminder：连续 3 轮未更新 todo
if (todo.hasOpenItems() && roundsWithoutTodo >= 3) {
    results.add(0, reminder);
}

// 2. Manual compress：如果 LLM 调用了 compress 工具
if (manualCompress) doAutoCompact(messageHistory, compactFocus);
```

## 持久化输出系统

大工具输出自动写入文件系统，在上下文中替换为标记：

```java
// bash 输出超 30,000 字符 / read_file 输出超 50,000 字符时触发
maybePersistOutput(toolUseId, output, triggerChars);
```

标记格式：

```xml
<persisted-output path=".task_outputs/tool-results/xxx.txt" size="1.2MB">
  前 2000 字符预览...
</persisted-output>
```

这使得大文件读取不会撑爆上下文窗口。

## Microcompact 机制

保留最近 3 个 tool_result turn，替换更早的非 read_file 结果为短摘要：

```java
// 原始结果：
"file1.java\nline 1\nline 2\n..."  // 几千字符

// 压缩后：
"[Previous: used bash]"
```

保留规则：`read_file` 的结果不压缩（因为模型经常需要回看文件内容）。

## 压缩的三个层级

| 层级 | 触发方式 | 行为 |
|------|----------|------|
| Micro | 每轮自动 | 替换旧 tool_result 为短摘要 |
| Auto | token 估算超 100K | LLM 生成摘要 → 替换整个历史 |
| Manual | `/compact` 命令或 compress 工具 | 同 auto，可带 focus 参数 |

Manual compress 的 `focus` 参数会追加到摘要 prompt：

```java
if (focus != null) summaryPrompt += "Pay special attention to: " + focus;
```

## Teammate 生命周期

SFull 的 teammate 拥有完整的 WORK → IDLE → WORK 循环：

```text
spawn_teammate
  → 虚拟线程启动 teammateLoop
  → WORK PHASE：标准 agent 循环，最多 50 轮
  → IDLE PHASE：轮询收件箱 + 扫描未认领任务
  → 有工作 → 恢复 WORK（身份再注入 + 任务提示注入）
  → 超时 60 秒无工作 → shutdown
```

Teammate 的 dispatch 使用同样的 `runBash`/`runRead`/`runWrite`/`runEdit`，也支持持久化输出。

## REPL 命令

```text
/compact   — 手动触发对话压缩
/tasks     — 列出所有任务
/team      — 列出所有 teammate 及状态
/inbox     — 读取 lead 收件箱
q / exit   — 退出
```

## 内部模块一览

### TodoManager（s03）

内存态待办清单。最多 20 项，最多 1 个 `in_progress`。

### SkillRegistry（s05）

启动时扫描 `skills/` 目录，按需加载 `SKILL.md` 文件。

### BackgroundManager（s13）

Virtual Thread 线程池，`run()` 返回 task_id，`drain()` 返回已完成的结果列表。

### MessageBus（s15）

JSONL 邮箱通信。`send()` 写入收件箱文件，`readInbox()` 读取并清空。含 `VALID_MSG_TYPES` 验证。

## 初学者最容易忽略的地方

### 1. 以为 SFull 只是"把代码拼在一起"

不是。

SFull 的真正难点在于：

- 多个机制共享同一个循环时，**执行顺序**为什么是这样
- messageHistory 的设计使得 microcompact 可以安全修改旧结果
- 持久化输出让大文件读取不会撑爆上下文

### 2. 忽略 messageHistory 和 paramsBuilder 的关系

`paramsBuilder` 是 append-only 的，`Message` 和 `ContentBlockParam` 是不可变的。

所以 SFull 维护独立的 `messageHistory` 列表，每轮从 history 重建 paramsBuilder。

这使得 microcompact 和 auto-compact 可以修改历史而不受不可变性约束。

### 3. 忘记工具结果必须和 tool_use 配对

Anthropic API 要求每个 `tool_result` 必须有对应的 `tool_use_id`。

Microcompact 替换旧结果时保留了原始 `toolUseId`，确保配对关系不被破坏。

## 教学边界

SFull 的教学目标是：

**让你看到"所有机制同时运行"的完整代码。**

它不讲解每个机制的工作原理（那是 s01–s19 各章的事），而是展示：

- 多机制共存时的代码组织
- 共享循环中预处理和后处理的执行顺序
- 消息历史管理如何同时支持 microcompact 和 auto-compact

## 变更对比（S19 → SFull）

| 组件 | S19 | SFull |
|------|-----|-------|
| 工具数量 | MCP 路由 + 原生工具 | 23 个工具（无 MCP 路由） |
| 消息历史 | paramsBuilder 直接追加 | messageHistory 列表 + 每轮重建 |
| Microcompact | （无） | 保留最近 3 个 tool_result turn |
| 持久化输出 | （无） | 大输出写入 .task_outputs/ |
| 压缩 focus | （无） | compress 工具支持 focus 参数 |
| 内部模块 | MCPClient + PluginLoader | TodoManager + SkillRegistry + BackgroundManager + MessageBus |
| Teammate | （无） | 完整 WORK/IDLE 生命周期 |
| 消息验证 | CapabilityPermissionGate | VALID_MSG_TYPES 验证 |
| 任务显示 | 列表 | 含 blockedBy 信息 |

## 学完这课后，你应该能回答

- SFull 和 s01–s19 的关系是什么？
- 为什么需要独立的 messageHistory 而不是直接操作 paramsBuilder？
- 每轮 LLM 调用前的 4 步预处理分别做什么？顺序能否打乱？
- 持久化输出解决了什么问题？
- Microcompact 的保留规则是什么？

---

**一句话记住：SFull 不是新概念，而是所有概念的汇合——让你看到它们同时运行时的完整代码。**

## 运行

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.SFullAgent"
```

REPL 命令：

```
/compact   # 手动压缩对话
/tasks     # 列出所有任务
/team      # 列出 teammate
/inbox     # 查看 lead 收件箱
```

## 试一试

### 启动

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.SFullAgent"
```

启动时确认输出 `mini-agent-4j SFullAgent | 23 tools | /compact /tasks /team /inbox`，并显示可用技能列表。

### 案例 1：基础工具 + TodoWrite + Nag Reminder

> 使用文件操作工具和 TodoWrite，观察 nag reminder 触发。

先让 agent 创建一个待办清单：

```
帮我创建一个待办清单：1) 读取 pom.xml 前 5 行 2) 列出 src 目录结构 3) 写一个测试文件
```

然后让 agent 只做前两项，不做第三项：

```
完成前两项待办，但不要更新待办列表
```

观察要点：
- `TodoWrite` 被调用时，待办列表渲染为 `[ ]`/`[>]`/`[x]` 格式
- 连续 3 轮没有调用 `TodoWrite` 后，工具结果前出现 `<reminder>Update your todos.</reminder>`
- `write_file` 输出格式为 `Wrote N bytes to <path>`，包含文件路径
- `read_file` 返回的文件内容超过 50,000 字符时自动触发持久化输出（`<persisted-output>` 标记）

### 案例 2：Task 系统 + 后台执行 + 消息验证

> 创建持久化任务、用后台执行命令、测试消息验证。

创建任务并设置依赖关系：

```
创建一个任务：分析项目结构
```

```
创建另一个任务：写分析报告，被第一个任务阻塞
```

用后台任务执行长时间命令：

```
用 background_run 后台执行：find . -name "*.java" | wc -l
```

测试消息验证：

```
给一个不存在的 teammate 发消息，msg_type 为 "invalid_type"
```

观察要点：
- `.tasks/` 目录下生成 `task_1.json`、`task_2.json`
- `task_update` 设置 `add_blocked_by` 后，`task_list` 显示 `(blocked by: [1])`
- `background_run` 返回 `task_id`，`check_background` 查询状态
- 下一轮 LLM 调用前，后台完成结果通过 `<background-results>` 注入对话
- `send_message` 使用无效 `msg_type` 时返回 `Error: Invalid msg_type 'invalid_type'`

### 案例 3：Teammate 生命周期 + 收件箱 + Manual Compress

> 生成 teammate 并观察完整生命周期，然后手动压缩对话。

生成一个 teammate：

```
生成一个叫 alice 的队友（角色 coder），让她统计 src 目录下的 Java 文件数量
```

等 alice 进入 idle 后通过收件箱唤醒：

```
给 alice 发消息："请查看 pom.xml 的行数"
```

查看 teammate 状态后手动压缩：

```
/team
```

```
/compact
```

观察要点：
- `spawn_teammate` 启动虚拟线程，`/team` 显示 `alice (coder): working`
- alice 完成工作后调用 `idle`，状态变为 `idle`
- 发送消息后，alice 的 IDLE 轮询发现收件箱非空，状态恢复为 `working`
- alice 收件箱中的消息包含 `from`、`content`、`timestamp` 字段
- `/compact` 触发 auto-compact：生成摘要、保存 transcript 到 `.transcripts/`、重建 messageHistory
- 压缩后日志显示 `[auto-compact triggered]`

### 案例 4：Microcompact + 持久化输出 + compress focus

> 连续触发多次工具调用，观察 microcompact 和持久化输出行为。

读取一个大文件（如果项目中有）或触发多次 bash 命令：

```
读取 src/main/java/com/example/agent/sessions/SFullAgent.java 的内容
```

```
运行命令：find . -name "*.java" -exec wc -l {} +
```

```
运行命令：cat pom.xml
```

然后使用带 focus 的压缩：

```
压缩对话，focus 设为 "task system and teammate lifecycle"
```

观察要点：
- `read_file` 结果在 microcompact 中被保留（PRESERVE_RESULT_TOOLS 包含 "read_file"）
- bash 结果在超过 3 个 tool_result turn 后，旧的被替换为 `[Previous: used bash]`
- 如果 bash 输出超过 30,000 字符，触发持久化输出：上下文中只保留 `<persisted-output>` 标记（含路径和前 2000 字符预览）
- 持久化文件写入 `.task_outputs/tool-results/` 目录
- `compress` 工具带 focus 参数时，摘要 prompt 包含 `Pay special attention to: task system and teammate lifecycle`
- 压缩后 `messageHistory` 被清空并替换为摘要文本
