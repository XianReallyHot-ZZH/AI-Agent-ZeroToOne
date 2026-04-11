# s07：权限系统

`... s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12 ...`

> *"安全是一个管线，不是一个布尔值。"* —— 每次工具调用都经过权限管线后才会真正执行。

## 课程目标

理解如何为 Agent 构建一个多层权限管线，让模型的能力在可控范围内运行。权限不是简单的"允许/拒绝"开关，而是一条有序的决策管线。

## 问题

模型可以调用 bash、写入文件、编辑代码。如果不加控制，一次幻觉就可能导致 `rm -rf /`。但完全禁止一切又让 Agent 失去价值。我们需要一个分层的权限系统：有些操作始终禁止，有些始终允许，有些需要用户确认。

## 方案

权限管线按优先级依次检查，第一个匹配即生效：

```
工具调用请求
      |
      v
[Bash 安全校验] ── 严重模式(sudo/rm -rf) → 直接 deny
      |             其他模式 → ask
      v
[deny 规则] ── 匹配 → deny（不可绕过）
      |
      v
[mode 检查] ── plan 模式：拒绝写操作，允许读操作
      |         auto 模式：自动允许读操作，写操作继续
      v
[allow 规则] ── 匹配 → allow
      |
      v
[ask user] ── 无规则匹配，询问用户 (y/n/always)
```

三种权限模式：

| 模式     | 读操作 | 写操作         |
|----------|--------|----------------|
| default  | 需确认 | 需确认         |
| plan     | 允许   | 拒绝（只读模式）|
| auto     | 自动   | 需确认         |

## 核心概念

### BashSecurityValidator —— 命令安全校验

基于正则表达式检测危险命令模式：

```java
static class BashSecurityValidator {
    private static final List<AbstractMap.SimpleEntry<String, Pattern>> VALIDATORS = List.of(
        new AbstractMap.SimpleEntry<>("shell_metachar", Pattern.compile("[;&|`$]")),
        new AbstractMap.SimpleEntry<>("sudo", Pattern.compile("\\bsudo\\b")),
        new AbstractMap.SimpleEntry<>("rm_rf", Pattern.compile("\\brm\\s+(-[a-zA-Z]*)?r")),
        new AbstractMap.SimpleEntry<>("cmd_substitution", Pattern.compile("\\$\\(")),
        new AbstractMap.SimpleEntry>("ifs_injection", Pattern.compile("\\bIFS\\s*="))
    );

    private static final Set<String> SEVERE_PATTERNS = Set.of("sudo", "rm_rf");
}
```

严重模式直接拒绝，其他模式升级为询问用户。

### PermissionManager —— 权限管线管理器

```java
Map<String, String> check(String toolName, Map<String, Object> toolInput,
                          BashSecurityValidator bashValidator) {
    // Step 0: Bash 安全校验
    // Step 1: deny 规则（不可绕过）
    // Step 2: 基于模式的决策（plan/auto）
    // Step 3: allow 规则
    // Step 4: ask user（无规则匹配时）
}
```

### 熔断器

```java
// 连续拒绝达到阈值时发出警告
consecutiveDenials++;
if (consecutiveDenials >= maxConsecutiveDenials) {
    System.out.println("[3 consecutive denials -- consider switching to plan mode]");
}
```

### "always" 回答持久化

用户回答 "always" 时，动态添加 allow 规则：

```java
if ("always".equals(answer)) {
    rules.add(new HashMap<>(Map.of("tool", toolName, "path", "*", "behavior", "allow")));
}
```

## 关键代码片段

权限感知的 Agent 循环，在工具执行前检查权限：

```java
Map<String, String> decision = perms.check(toolName, input, bashValidator);
String behavior = decision.get("behavior");

if ("deny".equals(behavior)) {
    output = "Permission denied: " + reason;
} else if ("ask".equals(behavior)) {
    if (perms.askUser(toolName, input)) {
        output = handler.apply(input);
    } else {
        output = "Permission denied by user";
    }
} else {
    output = handler.apply(input);
}
```

REPL 命令支持运行时切换模式：

```
/mode plan     # 切换到只读模式
/mode auto     # 切换到自动批准读操作
/rules         # 查看当前规则列表
```

## 变更对比

| 组件          | S06         | S07                                |
|---------------|-------------|------------------------------------|
| 权限检查      | （无）      | BashSecurityValidator + PermissionManager |
| 权限模式      | （无）      | default / plan / auto              |
| deny 规则     | （无）      | rm -rf /、sudo 等                  |
| allow 规则    | （无）      | read_file 始终允许                 |
| 熔断器        | （无）      | 连续 3 次拒绝警告                  |
| Agent 循环    | 直接执行    | 权限管线包装                       |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S07PermissionSystem"
```

1. 选择 `plan` 模式，然后尝试 `在当前目录创建一个新文件`
2. 选择 `default` 模式，然后执行 `ls` 和 `写入文件`，观察权限提示差异
3. 输入 `/mode auto`，再执行同样的写操作，对比行为变化
4. 执行 `删除所有文件`，观察危险命令拦截

## 要点总结

1. 权限管线是多层过滤器：deny 规则 > mode 检查 > allow 规则 > ask user
2. Bash 安全校验器在 deny 规则之前运行，严重模式直接拒绝
3. 三种模式满足不同场景：plan（审计）、default（交互）、auto（高效）
4. 熔断器防止模型反复尝试被拒绝的操作
5. "always" 回答动态添加规则，减少后续确认
