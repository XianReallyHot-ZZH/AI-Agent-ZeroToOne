# s07：权限系统

`s00 > s01 > s02 > s03 > s04 > s05 > s06 > [ s07 ] > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *模型可以提出行动建议，但真正执行之前，必须先过安全关。*

## 建议联读

- 如果你开始把"模型提议动作"和"系统真的执行动作"混成一件事，先回 [`s00a-query-control-plane.md`](./s00a-query-control-plane.md)，重新确认 query 是怎么进入控制面的。
- 如果你还没彻底稳住"工具请求为什么不能直接落到 handler"，建议把 [`s02a-tool-control-plane.md`](./s02a-tool-control-plane.md) 放在手边一起读。
- 如果你在 `PermissionRule / PermissionDecision / tool_result` 这几层对象上开始打结，先回 [`data-structures.md`](./data-structures.md)，把状态边界重新拆开。

## 这一章要解决什么问题

到了 `s06`，你的 agent 已经能读文件、改文件、跑命令、做规划、压缩上下文。

问题也随之出现了：

- 模型可能会写错文件
- 模型可能会执行危险命令
- 模型可能会在不该动手的时候动手

所以从这一章开始，系统需要一条新的管道：

**"意图"不能直接变成"执行"，中间必须经过权限检查。**

## 先解释几个名词

### 什么是权限系统

权限系统不是"有没有权限"这样一个布尔值。

它更像一条管道，用来回答：

1. 这次调用要不要直接拒绝？
2. 能不能自动放行？
3. 剩下的要不要问用户？

### 什么是权限模式

权限模式是系统当前的总体风格。

例如：

- 谨慎一点：大多数操作都问用户
- 保守一点：只允许读，不允许写
- 流畅一点：简单安全的操作自动放行

### 什么是规则

规则就是"遇到某种工具调用时，该怎么处理"的小条款。

最小规则通常包含三部分：

```java
Map.of(
    "tool", "bash",
    "content", "sudo *",
    "behavior", "deny"
)
```

意思是：

- 针对 `bash`
- 如果命令内容匹配 `sudo *`
- 就拒绝

## 最小心智模型

如果你是从 0 开始手写，一个最小但正确的权限系统只需要四步：

```text
tool_call
  |
  v
1. deny rules     -> 命中了就拒绝
  |
  v
2. mode check     -> 根据当前模式决定
  |
  v
3. allow rules    -> 命中了就放行
  |
  v
4. ask user       -> 剩下的交给用户确认
```

这四步已经能覆盖教学仓库 80% 的核心需要。

## 关键数据结构

### 1. PermissionRule（权限规则）

Java 实现中使用 `Map<String, String>` 表示规则：

```java
// deny 规则：拒绝包含 sudo 的 bash 命令
Map.of("tool", "bash", "content", "sudo *", "behavior", "deny")

// allow 规则：允许所有路径的 read_file
Map.of("tool", "read_file", "path", "*", "behavior", "allow")
```

你不一定一开始就需要 `path` 和 `content` 都支持。
但规则至少要能表达：

- 针对哪个工具
- 命中后怎么处理

### 2. PermissionMode（权限模式）

```java
private static final List<String> MODES = List.of("default", "plan", "auto");
```

推荐先实现的 3 种模式：

| 模式 | 含义 | 适合什么场景 |
|---|---|---|
| `default` | 未命中规则时问用户 | 日常交互 |
| `plan` | 只允许读，不允许写 | 计划、审查、分析 |
| `auto` | 简单安全操作自动过，危险操作再问 | 高流畅度探索 |

先有这三种，你就已经有了一个可用的权限系统。

### 3. PermissionDecision（权限决策结果）

```java
Map.of(
    "behavior", "allow",   // "allow" | "deny" | "ask"
    "reason", "why this decision was made"
)
```

这三个结构已经足够搭起最小系统。

## 为什么顺序是这样

### 第 1 步先看 deny rules

因为有些东西不应该交给"模式"去决定。

比如：

- 明显危险的命令
- 明显越界的路径

这些应该优先挡掉。

### 第 2 步看 mode

因为模式决定当前会话的大方向。

例如在 `plan` 模式下，系统就应该天然更保守。

### 第 3 步看 allow rules

有些安全、重复、常见的操作可以直接过。

比如：

- 读文件
- 搜索代码
- 查看 git 状态

### 第 4 步才 ask

前面都没命中的灰区，才交给用户。

## Bash 为什么值得单独讲

所有工具里，`bash` 通常最危险。

因为：

- `read_file` 只能读文件
- `write_file` 只能写文件
- 但 `bash` 几乎能做任何事

所以你不能只把 bash 当成一个普通字符串。

一个更成熟的系统，通常会把 bash 当成一门小语言来检查。

哪怕教学版不做完整语法分析，也建议至少先挡住这些明显危险点：

- `sudo`
- `rm -rf`
- 命令替换
- 可疑重定向
- 明显的 shell 元字符拼接

Java 实现中，`BashSecurityValidator` 用正则表达式检测这些模式：

```java
static class BashSecurityValidator {
    private static final List<AbstractMap.SimpleEntry<String, Pattern>> VALIDATORS = List.of(
        new AbstractMap.SimpleEntry<>("shell_metachar", Pattern.compile("[;&|`$]")),
        new AbstractMap.SimpleEntry<>("sudo",           Pattern.compile("\\bsudo\\b")),
        new AbstractMap.SimpleEntry<>("rm_rf",          Pattern.compile("\\brm\\s+(-[a-zA-Z]*)?r")),
        new AbstractMap.SimpleEntry<>("cmd_substitution", Pattern.compile("\\$\\(")),
        new AbstractMap.SimpleEntry<>("ifs_injection",  Pattern.compile("\\bIFS\\s*="))
    );

    private static final Set<String> SEVERE_PATTERNS = Set.of("sudo", "rm_rf");

    /** 校验 bash 命令，返回所有校验失败项 */
    public List<String[]> validate(String command) { ... }

    /** 人类可读的校验失败描述 */
    public String describeFailures(String command) { ... }

    /** 检查失败项中是否包含严重模式（sudo、rm_rf） */
    public boolean hasSevereFailure(List<String[]> failures) { ... }
}
```

严重模式（`sudo`、`rm_rf`）直接拒绝，其他模式升级为询问用户。

这背后的核心思想只有一句：

**bash 不是普通文本，而是可执行动作描述。**

## 初学者怎么把这章做对

### 第一步：先做 3 个模式

不要一开始就做 6 个模式、10 个来源、复杂 classifier。

先稳稳做出：

- `default`
- `plan`
- `auto`

### 第二步：先做 deny / allow 两类规则

这已经足够表达很多现实需求。

### 第三步：给 bash 加最小安全检查

哪怕只是模式匹配版，也比完全裸奔好很多。

### 第四步：加拒绝计数（熔断器）

如果 agent 连续多次被拒绝，说明它可能卡住了。

Java 实现中的熔断器：

```java
consecutiveDenials++;
if (consecutiveDenials >= maxConsecutiveDenials) {
    System.out.println("[3 consecutive denials -- consider switching to plan mode]");
}
```

这时可以：

- 给出提示
- 建议切到 `plan`
- 让用户重新澄清目标

## 这章不应该讲太多什么

为了不打乱初学者心智，这章不应该过早陷入：

- 企业策略源的全部优先级
- 非常复杂的自动分类器
- 产品环境里的所有无头模式细节
- 某个特定生产代码里的全部 validator 名称

这些东西存在，但不属于第一层理解。

第一层理解只有一句话：

**任何工具调用，都不应该直接执行；中间必须先过一条权限管道。**

## 这一章和后续章节的关系

- `s07` 决定"能不能执行"
- `s08` 决定"执行前后还能不能插入额外逻辑"
- `s10` 会把当前模式和权限说明放进 prompt 组装里

所以这章是后面很多机制的安全前提。

## 学完这章后，你应该能回答

- 为什么权限系统不是一个简单开关？
- 为什么 deny 要先于 allow？
- 为什么要先做 3 个模式，而不是一上来做很复杂？
- 为什么 bash 要被特殊对待？

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S07PermissionSystem"
```

1. 选择 `plan` 模式，然后尝试"在当前目录创建一个新文件"
2. 选择 `default` 模式，然后执行 `ls` 和"写入文件"，观察权限提示差异
3. 输入 `/mode auto`，再执行同样的写操作，对比行为变化
4. 执行"删除所有文件"，观察危险命令拦截

---

**一句话记住：权限系统不是为了让 agent 更笨，而是为了让 agent 的行动先经过一道可靠的安全判断。**
