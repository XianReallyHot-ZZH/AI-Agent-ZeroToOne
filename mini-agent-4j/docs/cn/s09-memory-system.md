# s09: 记忆系统

`s00 > s01 > s02 > s03 > s04 > s05 > s06 > s07 > s08 > [ s09 ] > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *不是所有信息都该进入 memory；只有跨会话仍然有价值的信息，才值得留下。*

## 但先立一个边界：memory 不是什么都存

这是这一章最容易讲歪的地方。

memory 不是"把一切有用信息都记下来"。

如果你这样做，很快就会出现两个问题：

1. memory 变成垃圾堆，越存越乱
2. agent 开始依赖过时记忆，而不是读取当前真实状态

所以这章必须先立一个原则：

**只有那些跨会话仍然有价值，而且不能轻易从当前仓库状态直接推出来的信息，才适合进入 memory。**

## 建议联读

- 如果你还把 memory 想成"更长一点的上下文窗口"，先回 [`s06-context-compact.md`](./s06-context-compact.md)，重新确认 compact 和长期记忆是两套机制。
- 如果你在 `messages[]`、摘要块、memory store 这三层之间开始读混，建议边看边对照 [`data-structures.md`](./data-structures.md)。
- 如果你准备继续读 `s10`，最好把 [`s10a-message-prompt-pipeline.md`](./s10a-message-prompt-pipeline.md) 放在旁边，因为 memory 真正重要的是它怎样重新进入下一轮输入。

## 这一章要解决什么问题

如果一个 agent 每次新会话都完全从零开始，它就会不断重复忘记这些事情：

- 用户长期偏好
- 用户多次纠正过的错误
- 某些不容易从代码直接看出来的项目约定
- 某些外部资源在哪里找

这会让系统显得"每次都像第一次合作"。

所以需要 memory。

## 先解释几个名词

### 什么是"跨会话"

意思是：

- 当前对话结束了
- 下次重新开始一个新对话
- 这条信息仍然可能有用

### 什么是"不可轻易重新推导"

例如：

- 用户明确说"我讨厌这种写法"
- 某个架构决定背后的真实原因是合规要求
- 某个团队总在某个外部看板里跟踪问题

这些东西，往往不是你重新扫一遍代码就能立刻知道的。

## 最适合先教的 4 类 memory

### 1. `user`

用户偏好。

例如：

- 喜欢什么代码风格
- 回答希望简洁还是详细
- 更偏好什么工具链

### 2. `feedback`

用户明确纠正过你的地方。

例如：

- "不要这样改"
- "这个判断方式之前错过"
- "以后遇到这种情况要先做 X"

### 3. `project`

这里只保存**不容易从代码直接重新看出来**的项目约定或背景。

例如：

- 某个设计决定是因为合规而不是技术偏好
- 某个目录虽然看起来旧，但短期内不能动
- 某条规则是团队故意定下来的，不是历史残留

### 4. `reference`

外部资源指针。

例如：

- 某个问题单在哪个看板里
- 某个监控面板在哪里
- 某个资料库在哪个 URL

## 哪些东西不要存进 memory

这是比"该存什么"更重要的一张表：

| 不要存的东西 | 为什么 |
|---|---|
| 文件结构、函数签名、目录布局 | 这些可以重新读代码得到 |
| 当前任务进度 | 这属于 task / plan，不属于 memory |
| 临时分支名、当前 PR 号 | 很快会过时 |
| 修 bug 的具体代码细节 | 代码和提交记录才是准确信息 |
| 密钥、密码、凭证 | 安全风险 |

这条边界一定要稳。

否则 memory 会从"帮助系统长期变聪明"变成"帮助系统长期产生幻觉"。

## 最小心智模型

```text
conversation
   |
   | 用户提到一个长期重要信息
   v
save_memory 工具（第 5 个工具）
   |
   v
.memory/
  ├── MEMORY.md        # 索引（200 行上限）
  ├── prefer_tabs.md
  ├── feedback_tests.md
  └── incident_board.md
   |
   v
下次新会话开始时 MemoryManager.loadAll() 重新加载
```

## memory、task、plan、CLAUDE.md 的边界

这是最值得初学者反复区分的一组概念。

| 概念 | 职责 | 生命周期 |
|---|---|---|
| memory | 保存跨会话仍有价值、不易重新推导的信息 | 持久，跨会话 |
| task | 保存当前工作要做什么、依赖关系如何、进度如何 | 当前任务 |
| plan | 保存"这一轮我要怎么做"的过程性安排 | 当前轮次 |
| CLAUDE.md | 保存更稳定、更像长期规则的说明文本 | 项目级，很少变 |

一个简单判断法：

- 只对这次任务有用：`task / plan`
- 以后很多会话可能都还会有用：`memory`
- 属于长期系统级或项目级固定说明：`CLAUDE.md`

## 关键数据结构

### 1. MemoryManager -- 记忆管理器（静态内部类）

```java
static class MemoryManager {
    // 扫描 .memory/*.md，解析 frontmatter，加载到内存
    void loadAll();

    // 构建 Markdown 片段注入系统提示词
    String loadMemoryPrompt();

    // 写入 frontmatter 文件，重建索引
    String saveMemory(String name, String description, String memType, String content);

    // 解析 --- 分隔的 frontmatter 头部
    Map<String, String> parseFrontmatter(String text);

    // 重建 MEMORY.md 索引（200 行上限）
    void rebuildIndex();
}
```

内存中的核心数据结构是一个 `LinkedHashMap`：

```java
// name -> {description, type, content, file}
Map<String, Map<String, String>> memories = new LinkedHashMap<>();
```

### 2. 单条 memory 文件

最简单也最清晰的做法，是每条 memory 一个文件。

```markdown
---
name: prefer_tabs
description: 用户偏好使用 tabs 而非 spaces
type: user
---
项目代码统一使用 tabs 缩进，tab 宽度设为 4。
所有新文件必须遵循此约定。
```

这里的 `frontmatter` 可以理解成：

**放在正文前面的结构化元数据。**

它让系统先知道：

- 这条 memory 叫什么
- 大致是什么
- 属于哪一类

### 3. 索引文件 `MEMORY.md`

最小实现里，再加一个索引文件就够了：

```markdown
# Memory Index

- prefer_tabs: User prefers tabs for indentation [user]
- avoid_mock_heavy_tests: User dislikes mock-heavy tests [feedback]
```

索引的作用不是重复保存全部内容。
它只是帮系统快速知道"有哪些 memory 可用"。

索引限制在 200 行以内，防止记忆膨胀：

```java
private static final int MAX_INDEX_LINES = 200;
```

### 4. DreamConsolidator -- 记忆整理器（可选）

可选的后阶段特性，防止记忆堆积为噪音。

七道门检查（全部通过才执行整理）：

```
门 1: enabled 标志
门 2: 记忆目录存在且有文件
门 3: 非 plan 模式
门 4: 24 小时冷却期
门 5: 10 分钟扫描节流
门 6: 最少 5 次会话
门 7: PID 锁（防止并发）
```

四阶段处理流程：

```
Orient    → 扫描 MEMORY.md 索引结构和分类
Gather    → 读取各条记忆文件的完整内容
Consolidate → 合并相关记忆，移除过期条目
Prune     → 强制执行 200 行索引上限
```

## 最小实现

### 第一步：定义 memory 类型

```java
private static final List<String> MEMORY_TYPES =
    List.of("user", "feedback", "project", "reference");
```

### 第二步：写一个 `save_memory` 工具

最小参数就四个：

```java
defineTool("save_memory", "Save a persistent memory that survives across sessions.",
    Map.of(
        "name", Map.of("type", "string",
            "description", "Short identifier (e.g. prefer_tabs, db_schema)"),
        "description", Map.of("type", "string",
            "description", "One-line summary of what this memory captures"),
        "type", Map.of("type", "string",
            "enum", List.of("user", "feedback", "project", "reference"),
            "description", "user=preferences, feedback=corrections, "
                         + "project=non-obvious project conventions or decision reasons, "
                         + "reference=external resource pointers"),
        "content", Map.of("type", "string",
            "description", "Full memory content (multi-line OK)")),
    List.of("name", "description", "type", "content"));
```

### 第三步：每条 memory 独立落盘

```java
String saveMemory(String name, String description, String memType, String content) {
    // 校验类型合法性
    if (!MEMORY_TYPES.contains(memType)) {
        return "Error: type must be one of " + MEMORY_TYPES;
    }

    // 清理名称，生成安全文件名
    String safeName = name.toLowerCase().replaceAll("[^a-zA-Z0-9_-]", "_");

    // 构建 frontmatter 格式
    String frontmatter = "---\n"
        + "name: " + name + "\n"
        + "description: " + description + "\n"
        + "type: " + memType + "\n"
        + "---\n"
        + content + "\n";

    // 写入文件，更新内存映射，重建索引
    Files.writeString(memoryDir.resolve(safeName + ".md"), frontmatter);
    memories.put(name, entry);
    rebuildIndex();
}
```

### 第四步：会话开始时重新加载

```java
// 启动时加载已有记忆
memoryMgr.loadAll();

int memCount = memoryMgr.size();
if (memCount > 0) {
    System.out.println("[" + memCount + " memories loaded into context]");
} else {
    System.out.println("[No existing memories. The agent can create them with save_memory.]");
}
```

### 第五步：把 memory section 接进系统输入

这一步会在 `s10` 的 prompt 组装里系统化。教学版的实现是每次 LLM 调用前动态重建系统提示词：

```java
private static String buildSystemPrompt() {
    List<String> parts = new ArrayList<>();
    parts.add("You are a coding agent at " + WORK_DIR + ". Use tools to solve tasks.");

    // 注入记忆内容（如果有的话）
    String memorySection = memoryMgr.loadMemoryPrompt();
    if (!memorySection.isEmpty()) {
        parts.add(memorySection);
    }

    // 注入记忆使用指南
    parts.add(MEMORY_GUIDANCE);
    return String.join("\n\n", parts);
}
```

在 Agent 循环中，每次调用 LLM 前都会重新构建系统提示词：

```java
while (true) {
    // 动态重建系统提示词（记忆感知）
    String systemPrompt = buildSystemPrompt();

    var loopParamsBuilder = MessageCreateParams.builder()
        .model(model)
        .maxTokens(8000L)
        .system(systemPrompt)   // 注入最新记忆
        .messages(paramsBuilder.build().messages());
    // ...
}
```

这样确保同一会话中刚保存的记忆，在下一轮 LLM 调用时即可见。

REPL 中可以用 `/memories` 命令查看当前所有记忆：

```
/memories     # 列出所有记忆
```

## 初学者最容易犯的错

### 错误 1：把代码结构也存进 memory

例如：

- "这个项目有 `src/` 和 `tests/`"
- "这个函数在 `app.py`"

这些都不该存。

因为系统完全可以重新去读。

### 错误 2：把当前任务状态存进 memory

例如：

- "我现在正在改认证模块"
- "这个 PR 还有两项没做"

这些是 task / plan，不是 memory。

### 错误 3：把 memory 当成绝对真相

memory 可能过时。

所以更稳妥的规则是：

**memory 用来提供方向，不用来替代当前观察。**

如果 memory 和当前代码状态冲突，优先相信你现在看到的真实状态。

## 从教学版到高完成度版：记忆系统还要补的 6 条边界

最小教学版只要先把"该存什么 / 不该存什么"讲清楚。
但如果你要把系统做到更稳、更像真实工作平台，下面这 6 条边界也必须讲清。

### 1. 不是所有 memory 都该放在同一个作用域

更完整系统里，至少要分清：

- `private`：只属于当前用户或当前 agent 的记忆
- `team`：整个项目团队都该共享的记忆

一个很稳的教学判断法是：

- `user` 类型，几乎总是 `private`
- `feedback` 类型，默认 `private`；只有它明确是团队规则时才升到 `team`
- `project` 和 `reference`，通常更偏向 `team`

这样做的价值是：

- 不把个人偏好误写成团队规范
- 不把团队规范只锁在某一个人的私有记忆里

### 2. 不只保存"你做错了"，也要保存"这样做是对的"

很多人讲 memory 时，只会想到纠错。

这不够。

因为真正能长期使用的系统，还需要记住：

- 哪种不明显的做法，用户已经明确认可
- 哪个判断方式，项目里已经被验证有效

也就是说，`feedback` 不只来自负反馈，也来自被验证的正反馈。

如果只存纠错，不存被确认有效的做法，系统会越来越保守，却不一定越来越聪明。

### 3. 有些东西即使用户要求你存，也不该直接存

这条边界一定要说死。

就算用户说"帮我记住"，下面这些东西也不应该直接写进 memory：

- 本周 PR 列表
- 当前分支名
- 今天改了哪些文件
- 某个函数现在在什么路径
- 当前正在做哪两个子任务

这些内容的问题不是"没有价值"，而是：

- 太容易过时
- 更适合存在代码、任务板、git 记录里
- 会把 memory 变成活动日志

更好的做法是追问一句：

> 这里面真正值得长期留下的、非显然的信息到底是什么？

### 4. memory 会漂移，所以回答前要先核对当前状态

memory 记录的是"曾经成立过的事实"，不是永久真理。

所以更稳的工作方式是：

1. 先把 memory 当作方向提示
2. 再去读当前文件、当前资源、当前配置
3. 如果冲突，优先相信你刚观察到的真实状态

这点对初学者尤其重要。
因为他们最容易把 memory 当成"已经查证过的答案"。

### 5. 用户说"忽略 memory"时，就当它是空的

这是一个很容易漏讲的行为边界。

如果用户明确说：

- "这次不要参考 memory"
- "忽略之前的记忆"

那系统更合理的处理不是：

- 一边继续用 memory
- 一边嘴上说"我知道但先忽略"

而是：

**在这一轮里，按 memory 为空来工作。**

### 6. 推荐具体路径、函数、外部资源前，要再验证一次

memory 很适合保存：

- 哪个看板通常有上下文
- 哪个目录以前是关键入口
- 某种项目约定为什么存在

但在你真的要对用户说：

- "去改 `src/auth.py`"
- "调用 `AuthManager`"
- "看这个 URL 就对了"

之前，最好再核对一次。

因为命名、路径、系统入口、外部链接，都是会变的。

所以更稳妥的做法不是：

> memory 里写过，就直接复述。

而是：

> memory 先告诉我去哪里验证；验证完，再给用户结论。

## 教学边界

这章最重要的，不是 memory 以后还能多自动、多复杂，而是先把存储边界讲清楚：

- 什么值得跨会话留下
- 什么只是当前任务状态，不该进 memory
- memory 和 task / plan / CLAUDE.md 各自负责什么

只要这几层边界清楚，教学目标就已经达成了。

更复杂的自动整合、作用域分层、自动抽取，都应该放在这个最小边界之后。

## 变更对比

| 组件 | S08 | S09 |
|---|---|---|
| 记忆管理 | （无） | MemoryManager 静态内部类 + .memory/ 目录 |
| 记忆类型 | （无） | user / feedback / project / reference |
| 记忆工具 | （无） | save_memory（第 5 个工具） |
| 系统提示词 | 静态字符串 | 每轮动态重建（注入记忆内容） |
| 记忆整理 | （无） | DreamConsolidator（7 门 + 4 阶段） |
| REPL 命令 | （无） | /memories 列出当前记忆 |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S09MemorySystem"
```

1. 输入 `请记住：我喜欢使用 tabs 而非 spaces 进行缩进`
2. 输入 `/memories` 查看已保存的记忆
3. 检查 `.memory/` 目录下生成的文件
4. 重启 Agent，观察记忆自动加载

## 学完这章后，你应该能回答

- 为什么 memory 不是"什么都记"？
- 什么样的信息适合跨会话保存？
- 为什么代码结构和当前任务状态不应该进 memory？
- memory 和 task / plan / CLAUDE.md 的边界是什么？

---

**一句话记住：memory 保存的是"以后还可能有价值、但当前代码里不容易直接重新看出来"的信息。**
