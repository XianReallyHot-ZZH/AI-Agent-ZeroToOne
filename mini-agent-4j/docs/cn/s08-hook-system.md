# s08：Hook 系统

`s00 > s01 > s02 > s03 > s04 > s05 > s06 > s07 > [ s08 ] > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *不改主循环代码，也能在关键时机插入额外行为。*

## 建议联读

- 如果你还在把 hook 想成"往主循环里继续塞 if/else"，先回 [`s02a-tool-control-plane.md`](./s02a-tool-control-plane.md)，重新确认主循环和控制面的边界。
- 如果你开始把主循环、tool handler、hook side effect 混成一层，建议先看 [`entity-map.md`](./entity-map.md)，把谁负责推进主状态、谁只是旁路观察分开。
- 如果你准备继续读后面的 prompt、recovery、teams，可以把 [`s00e-reference-module-map.md`](./s00e-reference-module-map.md) 一起放在旁边，因为从这一章开始"控制面 + 侧车扩展"会反复一起出现。

## 什么是 hook

到了 `s07`，我们已经能在工具执行前做权限判断。

但很多真实需求并不属于"允许 / 拒绝"这条线，而属于：

- 在某个固定时机顺手做一点事
- 不改主循环主体，也能接入额外规则
- 让用户或插件在系统边缘扩展能力

例如：

- 会话开始时打印欢迎信息
- 工具执行前做一次额外检查
- 工具执行后补一条审计日志

如果每增加一个需求，你都去修改主循环，主循环就会越来越重，最后谁都不敢动。

所以这一章要引入的机制是：

**主循环只负责暴露"时机"，真正的附加行为交给 hook。**

你可以把 `hook` 理解成一个"预留插口"。

意思是：

1. 主系统运行到某个固定时机
2. 把当前上下文交给 hook
3. hook 返回结果
4. 主系统再决定下一步怎么继续

最重要的一句话是：

**hook 让系统可扩展，但不要求主循环理解每个扩展需求。**

主循环只需要知道三件事：

- 现在是什么事件
- 要把哪些上下文交出去
- 收到结果以后怎么处理

## 最小心智模型

教学版先只讲 3 个事件：

- `SessionStart`
- `PreToolUse`
- `PostToolUse`

这样做不是因为系统永远只有 3 个事件，
而是因为初学者先把这 3 个事件学明白，就已经能自己做出一套可用的 hook 机制。

可以把它想成这条流程：

```text
主循环继续往前跑
  |
  +-- 到了某个预留时机
  |
  +-- 调用 hook runner
  |
  +-- 收到 hook 返回结果
  |
  +-- 决定继续、阻止、还是补充说明
```

## 教学版统一返回约定

这一章最容易把人讲乱的地方，就是"不同 hook 事件的返回语义"。

教学版建议先统一成下面这套规则：

| 退出码 | 含义 | stdout | stderr |
|---|---|---|---|
| `0` | 正常继续 | 可选 JSON（修改输入、追加上下文） | -- |
| `1` | 阻止当前动作 | -- | 阻断原因 |
| `2` | 注入一条补充消息，再继续 | -- | 要注入的文本 |

这套规则的价值不在于"最真实"，而在于"最容易学会"。

因为它让你先记住 hook 最核心的 3 种作用：

- 观察
- 拦截
- 补充

等教学版跑通以后，再去做"不同事件采用不同语义"的细化，也不会乱。

## 关键数据结构

### 1. HookEvent

Java 实现中，事件通过字符串常量和上下文 Map 传递：

```java
// 事件类型常量
private static final String EVENT_PRE_TOOL_USE  = "PreToolUse";
private static final String EVENT_POST_TOOL_USE = "PostToolUse";
private static final String EVENT_SESSION_START = "SessionStart";

// 事件上下文（Map<String, Object>）
Map<String, Object> hookContext = new LinkedHashMap<>();
hookContext.put("tool_name", toolName);
hookContext.put("tool_input", input);
```

它回答的是：

- 现在发生了什么事
- 这件事的上下文是什么

### 2. HookResult

```java
static class HookResult {
    boolean blocked = false;           // 是否被阻断
    String blockReason = null;         // 阻断原因（来自 stderr）
    List<String> messages = new ArrayList<>();  // 要注入的消息列表
    String permissionOverride = null;  // 权限决策覆盖
}
```

它回答的是：

- hook 想不想阻止主流程
- 要不要向模型补一条说明

### 3. HookRunner（HookManager）

```java
static class HookManager {
    HookResult runHooks(String event, Map<String, Object> context) { ... }
}
```

主循环不直接关心"每个 hook 的细节实现"。
它只把事件交给统一的 runner。

这就是这一章的关键抽象边界：

**主循环知道事件名，hook runner 知道怎么调扩展逻辑。**

Java 实现中，`HookManager` 从 `.hooks.json` 加载 Hook 定义：

```json
{
  "hooks": {
    "PreToolUse": [
      {"matcher": "bash", "command": "python check.py"}
    ],
    "PostToolUse": [
      {"matcher": "*", "command": "python log.py"}
    ],
    "SessionStart": [
      {"matcher": "*", "command": "python init.py"}
    ]
  }
}
```

每次 Hook 执行时，Agent 通过环境变量传递上下文：

```java
env.put("HOOK_EVENT", event);
env.put("HOOK_TOOL_NAME", toolName);
env.put("HOOK_TOOL_INPUT", inputJson);    // 截断到 10000 字符
env.put("HOOK_TOOL_OUTPUT", outputStr);   // 仅 PostToolUse
```

## 最小执行流程

先看最重要的 `PreToolUse` / `PostToolUse`：

```text
model 发起 tool_use
    |
    v
run_hook("PreToolUse", ...)
    |
    +-- exit 1 -> 阻止工具执行
    +-- exit 2 -> 先补一条消息给模型，再继续
    +-- exit 0 -> 直接继续（stdout 可修改工具输入）
    |
    v
执行工具
    |
    v
run_hook("PostToolUse", ...)
    |
    +-- exit 2 -> 追加补充说明
    +-- exit 0 -> 正常结束
```

再加上 `SessionStart`，一整套最小 hook 机制就立住了。

## 最小实现

### 第一步：准备事件到处理器的映射

Java 实现中，Hook 定义从配置文件加载，存储为 `Map<String, List<HookDefinition>>`：

```java
// Hook 定义结构
static class HookDefinition {
    final String matcher;   // 工具名过滤器（"*" 匹配所有，或指定工具名）
    final String command;   // 要执行的 shell 命令
}

// Hook 注册表：事件类型 -> Hook 定义列表
private final Map<String, List<HookDefinition>> hooks;
```

这里先用"一个事件对应一组处理函数"的最小结构就够了。

### 第二步：统一运行 hook

```java
HookResult runHooks(String event, Map<String, Object> context) {
    HookResult result = new HookResult();

    // 信任门控：不受信任的工作区不执行 Hook
    if (!checkWorkspaceTrust()) return result;

    for (HookDefinition hookDef : hooks.getOrDefault(event, List.of())) {
        // Matcher 过滤：检查当前工具是否匹配此 Hook
        if (!"*".equals(matcher) && !matcher.equals(toolName)) continue;

        // 执行子进程，解析退出码
        int exitCode = process.exitValue();
        if (exitCode == 1) {
            result.blocked = true;
            result.blockReason = stderrStr;
            return result;     // 阻断，不再继续
        }
        if (exitCode == 2) {
            result.messages.add(stderrStr);  // 注入消息
        }
    }
    return result;
}
```

教学版里先用"谁先返回阻止/注入，谁就优先"的简单规则。

### 第三步：接进主循环

```java
// PreToolUse Hook
HookManager.HookResult preResult = hookManager.runHooks("PreToolUse", hookContext);

// 注入 PreToolUse Hook 消息
for (String msg : preResult.messages) {
    toolResults.add(...);  // 消息回传给 LLM
}

// 如果被 Hook 阻断，跳过工具执行
if (preResult.blocked) {
    String output = "Tool blocked by PreToolUse hook: " + reason;
    toolResults.add(...);  // 阻断消息回传给 LLM
    continue;
}

// 执行工具（如果 Hook 修改了 tool_input，使用更新后的值）
Map<String, Object> effectiveInput =
    (Map<String, Object>) hookContext.getOrDefault("tool_input", input);
output = handler.apply(effectiveInput);

// PostToolUse Hook
hookContext.put("tool_output", output);
HookManager.HookResult postResult = hookManager.runHooks("PostToolUse", hookContext);

// 追加 PostToolUse Hook 消息到输出
for (String msg : postResult.messages) {
    output += "\n[Hook note]: " + msg;
}
```

这一步最关键的不是代码量，而是心智：

**hook 不是主循环的替代品，hook 是主循环在固定时机对外发出的调用。**

## 这一章的教学边界

如果你后面继续扩展平台，hook 事件面当然会继续扩大。

常见扩展方向包括：

- 生命周期事件：开始、结束、配置变化
- 工具事件：执行前、执行后、失败后
- 压缩事件：压缩前、压缩后
- 多 agent 事件：子 agent 启动、任务完成、队友空闲

但教学仓这里要守住一个原则：

**先把 hook 的统一模型讲清，再慢慢增加事件种类。**

不要一开始就把几十种事件、几十套返回语义全部灌给读者。

## 初学者最容易犯的错

### 1. 把 hook 当成"到处插 if"

如果还是散落在主循环里写条件分支，那还不是真正的 hook 设计。

### 2. 没有统一的返回结构

今天返回字符串，明天返回布尔值，后天返回整数，最后主循环一定会变乱。

### 3. 一上来就把所有事件做全

教学顺序应该是：

1. 先学会 3 个事件
2. 再学会统一返回协议
3. 最后才扩事件面

### 4. 忘了说明"教学版统一语义"和"高完成度细化语义"的区别

如果这层不提前说清，读者后面看到更复杂实现时会以为前面学错了。

其实不是学错了，而是：

**先学统一模型，再学事件细化。**

## 学完这一章，你应该真正掌握什么

学完以后，你应该能自己清楚说出下面几句话：

1. hook 的作用，是在固定时机扩展系统，而不是改写主循环。
2. hook 至少需要"事件名 + payload + 返回结果"这三样东西。
3. 教学版可以先用统一的 `0 / 1 / 2` 返回约定。
4. `PreToolUse` 和 `PostToolUse` 已经足够支撑最核心的扩展能力。

如果这 4 句话你已经能独立复述，说明这一章的核心心智已经建立起来了。

## 下一章学什么

这一章解决的是：

> 在固定时机插入行为。

下一章 `s09` 要解决的是：

> 哪些信息应该跨会话留下，哪些不该留。

也就是从"扩展点"进一步走向"持久状态"。

## 试一试

### 准备 Hook 脚本和配置

先创建 Hook 脚本目录和信任标记（Hook 只在受信任的工作区中运行）：

```sh
cd mini-agent-4j
mkdir -p hooks .claude
```

创建 `.claude/.claude_trusted` 空文件（工作区信任标记）。

创建 `hooks/block_sudo.py` —— PreToolUse 拦截脚本（退出码 1 阻断）：

```python
import os, sys, json
input_json = json.loads(os.environ.get("HOOK_TOOL_INPUT", "{}"))
command = input_json.get("command", "")
if "sudo" in command:
    print(f"Blocked: sudo detected in: {command}", file=sys.stderr)
    sys.exit(1)
```

创建 `hooks/audit_log.py` —— PostToolUse 注入脚本（退出码 2 注入消息）：

```python
import os, sys
tool_name = os.environ.get("HOOK_TOOL_NAME", "")
output = os.environ.get("HOOK_TOOL_OUTPUT", "")[:100]
print(f"[Audit] {tool_name} executed. Preview: {output}", file=sys.stderr)
sys.exit(2)
```

创建 `hooks/add_limit.py` —— PreToolUse 修改输入脚本（退出码 0 + JSON stdout）：

```python
import os, sys, json
input_json = json.loads(os.environ.get("HOOK_TOOL_INPUT", "{}"))
input_json["limit"] = 5
print(json.dumps({"updatedInput": input_json}))
sys.exit(0)
```

### 启动

```sh
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S08HookSystem"
```

### 案例 1：PreToolUse 拦截（退出码 1）

> 配置一个 PreToolUse Hook 拦截包含 sudo 的 bash 命令，验证阻断机制。

创建 `.hooks.json`：

```json
{
  "hooks": {
    "PreToolUse": [
      {"matcher": "bash", "command": "python hooks/block_sudo.py"}
    ]
  }
}
```

启动后执行：

```
帮我用 sudo 执行系统更新
```

观察要点：
- 日志出现 `[hook:PreToolUse] BLOCKED: Blocked: sudo detected in: sudo ...`
- 工具被阻断，模型收到 `Tool blocked by PreToolUse hook` 消息后调整策略
- matcher 为 `"bash"` —— 只对 bash 工具生效，read_file 等不触发此 Hook

再试一个正常命令：

```
列出当前目录下的文件
```

观察要点：
- bash 工具正常执行（不含 sudo，Hook 未拦截）
- 退出码 0（无输出）时 Hook 静默通过

### 案例 2：PostToolUse 注入补充信息（退出码 2）

> 添加 PostToolUse Hook，在工具执行后向模型注入审计信息。

更新 `.hooks.json`：

```json
{
  "hooks": {
    "PreToolUse": [
      {"matcher": "bash", "command": "python hooks/block_sudo.py"}
    ],
    "PostToolUse": [
      {"matcher": "*", "command": "python hooks/audit_log.py"}
    ]
  }
}
```

重启后执行：

```
帮我看看 pom.xml 的前 20 行
```

观察要点：
- `read_file` 执行后，PostToolUse Hook 触发
- 日志出现 `[hook:PostToolUse] INJECT: [Audit] read_file executed...`
- 模型收到的工具结果末尾追加了 `[Hook note]: [Audit] read_file executed...`
- matcher 为 `"*"` 表示匹配所有工具

### 案例 3：PreToolUse 修改工具输入（退出码 0 + JSON）

> 配置 PreToolUse Hook 通过 JSON stdout 静默修改工具输入参数。

更新 `.hooks.json`：

```json
{
  "hooks": {
    "PreToolUse": [
      {"matcher": "bash", "command": "python hooks/block_sudo.py"},
      {"matcher": "read_file", "command": "python hooks/add_limit.py"}
    ],
    "PostToolUse": [
      {"matcher": "*", "command": "python hooks/audit_log.py"}
    ]
  }
}
```

重启后执行：

```
帮我读取 README.md 的全部内容
```

观察要点：
- PreToolUse Hook 拦截 read_file，输出 `{"updatedInput": {"path": "README.md", "limit": 5}}`
- 日志出现 `[hook:PreToolUse] {"updatedInput": ...}` —— 退出码 0 + JSON stdout 的静默修改
- 模型实际只收到前 5 行内容（尽管用户要求读取全部，Hook 强制加了 limit）
- 这就是退出码 0 的"不改流程，但改参数"能力

### 案例 4：SessionStart + 多 Hook 协作

> 添加 SessionStart Hook 验证启动触发；同时观察三种 Hook 事件的完整协作链。

更新 `.hooks.json`：

```json
{
  "hooks": {
    "PreToolUse": [
      {"matcher": "bash", "command": "python hooks/block_sudo.py"},
      {"matcher": "read_file", "command": "python hooks/add_limit.py"}
    ],
    "PostToolUse": [
      {"matcher": "*", "command": "python hooks/audit_log.py"}
    ],
    "SessionStart": [
      {"matcher": "*", "command": "python -c \"import sys; print('Session initialized'); sys.exit(0)\""}
    ]
  }
}
```

重启后观察：

观察要点：
- 启动时（进入 REPL 前）日志出现 `[hook:SessionStart] Session initialized` —— 无需用户输入
- 执行任意工具，观察完整协作链：PreToolUse 检查/修改 → 工具执行 → PostToolUse 审计注入
- 删除 `.claude/.claude_trusted` 后重启，所有 Hook 不再执行（工作区信任门控生效）

---

**一句话记住：hook 不是主循环的替代品，而是主循环在固定时机对外发出的调用。**
