# s08：Hook 系统

`... s06 | s07 > s08 > s09 > s10 > s11 > s12 ...`

> *"不改循环，扩展 Agent。"* —— Hook 是主循环的扩展点，不改写循环本身即可注入行为。

## 课程目标

理解如何通过 Hook 机制在不修改 Agent 核心循环的情况下，注入预处理和后处理逻辑。Hook 让 Agent 变成一个可插拔的平台。

## 问题

不同的团队和项目有不同的需求：代码审查、日志记录、安全策略、通知推送。如果把这些都硬编码进 Agent 循环，代码会膨胀到不可维护。我们需要一种机制让使用者在不改循环的情况下注入自定义行为。

## 方案

Hook 是在特定事件点触发的子进程。它们在工具执行的前后运行，可以拦截、修改或追加信息：

```
                Agent 循环
                    |
          +---------+---------+
          v                   v
   [PreToolUse Hook]    [PostToolUse Hook]
          |                   |
    退出码 0 → 继续      退出码 0 → 继续
    退出码 1 → 阻断      退出码 2 → 注入消息
    退出码 2 → 注入消息   (可修改 tool_output)
```

退出码契约：

| 退出码 | 含义     | stdout               | stderr        |
|--------|----------|----------------------|---------------|
| 0      | 继续     | 可选 JSON（修改输入） | --            |
| 1      | 阻断执行 | --                   | 阻断原因      |
| 2      | 注入消息 | --                   | 要注入的文本  |

## 核心概念

### 三种 Hook 事件

- **SessionStart**：会话启动时触发，用于初始化
- **PreToolUse**：工具执行前触发，可拦截或修改工具输入
- **PostToolUse**：工具执行后触发，可追加额外信息

### HookManager —— Hook 管理器

从 `.hooks.json` 加载 Hook 定义，根据事件类型和 matcher 筛选并执行：

```java
static class HookManager {
    HookResult runHooks(String event, Map<String, Object> context) {
        // 1. 检查工作区信任（.claude/.claude_trusted）
        // 2. 遍历匹配的 Hook（根据 matcher 过滤）
        // 3. 构建环境变量（HOOK_EVENT, HOOK_TOOL_NAME, HOOK_TOOL_INPUT）
        // 4. 执行子进程（30 秒超时）
        // 5. 根据退出码解析结果
    }
}
```

### Hook 配置文件

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

### 工作区信任

Hook 只在受信任的工作区中运行。信任标记文件：`.claude/.claude_trusted`。

### Hook 环境变量

每次 Hook 执行时，Agent 通过环境变量传递上下文：

```java
env.put("HOOK_EVENT", event);
env.put("HOOK_TOOL_NAME", toolName);
env.put("HOOK_TOOL_INPUT", inputJson);    // 截断到 10000 字符
env.put("HOOK_TOOL_OUTPUT", outputStr);   // 仅 PostToolUse
```

## 关键代码片段

Hook 感知的 Agent 循环，在工具执行前后调用 Hook：

```java
// PreToolUse Hook
HookManager.HookResult preResult = hookManager.runHooks("PreToolUse", hookContext);

if (preResult.blocked) {
    // 被阻断，跳过工具执行
    output = "Tool blocked by PreToolUse hook: " + reason;
    continue;
}

// 执行工具
output = handler.apply(effectiveInput);

// PostToolUse Hook
hookContext.put("tool_output", output);
HookManager.HookResult postResult = hookManager.runHooks("PostToolUse", hookContext);
for (String msg : postResult.messages) {
    output += "\n[Hook note]: " + msg;
}
```

退出码 0 的 stdout 可包含结构化 JSON，支持修改工具输入：

```java
Map<String, Object> hookOutput = parseSimpleJson(stdoutStr);
// updatedInput：更新工具输入参数
// additionalContext：追加额外上下文
// permissionDecision：覆盖权限决策
```

## 变更对比

| 组件          | S07         | S08                                 |
|---------------|-------------|-------------------------------------|
| Hook 系统     | （无）      | HookManager + .hooks.json           |
| Hook 事件     | （无）      | SessionStart / PreToolUse / PostToolUse |
| 退出码契约    | （无）      | 0(继续) / 1(阻断) / 2(注入)        |
| 工作区信任    | （无）      | .claude/.claude_trusted 标记文件    |
| Agent 循环    | 权限感知    | Hook 感知（工具前后包装 Hook）      |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S08HookSystem"
```

1. 创建 `.hooks.json` 文件，配置一个 PreToolUse Hook 拦截所有 bash 命令
2. 在 Agent 中执行 `列出当前目录的文件`，观察 Hook 触发
3. 修改 Hook 返回退出码 1，观察工具被阻断
4. 创建一个 PostToolUse Hook，在每次工具执行后注入额外提示

## 要点总结

1. Hook 是子进程，通过退出码和 stdout/stderr 与 Agent 通信
2. 三种退出码：0（继续）、1（阻断）、2（注入消息）
3. Matcher 机制允许 Hook 只关注特定工具（`"*"` 匹配所有）
4. 工作区信任门控防止恶意目录中的 Hook 被执行
5. Hook 通过环境变量接收上下文，不需要解析命令行参数
