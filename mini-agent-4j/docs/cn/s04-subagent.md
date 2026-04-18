# s04: Subagents (子智能体)

`s00 > s01 > s02 > s03 > [ s04 ] > s05 > s06 > s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *一个大任务，不一定要塞进一个上下文里做完。*

## 这一章到底要解决什么问题

当 agent 连续做很多事时，`messages` 会越来越长。

比如用户只问：

> "这个项目用什么测试框架？"

但 agent 可能为了回答这个问题：

- 读了好几个配置文件
- 搜了依赖声明
- 跑了测试命令

真正有价值的最终答案，可能只有一句话。

如果这些中间过程都永久堆在父对话里，后面的问题会越来越难回答，因为上下文被大量局部任务的噪声填满了。

这就是子智能体要解决的问题：

**把局部任务放进独立上下文里做，做完只把必要结果带回来。**

## 先解释几个名词

### 什么是"父智能体"

当前正在和用户对话、持有主 `messages` 的 agent，就是父智能体。

### 什么是"子智能体"

父智能体临时派生出来，专门处理某个子任务的 agent，就是子智能体。

### 什么叫"上下文隔离"

意思是：

- 父智能体有自己的 `messages`
- 子智能体也有自己的 `messages`
- 子智能体的中间过程不会自动写回父智能体

## 最小心智模型

```text
Parent agent
  |
  | 1. 决定把一个局部任务外包出去
  v
Subagent
  |
  | 2. 在自己的上下文里读文件 / 搜索 / 执行工具
  v
Summary
  |
  | 3. 只把最终摘要或结果带回父智能体
  v
Parent agent continues
```

最重要的点只有一个：

**子智能体的价值，不是"多一个模型实例"本身，而是"多一个干净上下文"。**

## 最小实现长什么样

### 第一步：给父智能体一个 `task` 工具

父智能体需要一个工具，让模型可以主动说：

> "这个子任务我想交给一个独立上下文去做。"

```java
defineTool("task",
    "Spawn a subagent with fresh context. It shares the filesystem but not conversation history.",
    Map.of(
        "prompt",      Map.of("type", "string"),
        "description", Map.of("type", "string",
                        "description", "Short description of the task")),
    List.of("prompt"))
```

### 第二步：子智能体使用自己的消息列表

```java
private static String runSubagent(AnthropicClient client, String model, String prompt) {
    var subBuilder = MessageCreateParams.builder()
        .model(model)
        .maxTokens(8000L)
        .system(SUBAGENT_SYSTEM);  // 子 Agent 有自己独立的系统提示词

    for (Tool tool : CHILD_TOOLS) {
        subBuilder.addTool(tool);
    }

    subBuilder.addUserMessage(prompt);  // 全新的上下文，不继承父对话
    ...
}
```

这就是隔离的关键。

不是共享父智能体的 `messages`，而是从一份新的列表开始。

### 第三步：子智能体只拿必要工具

子智能体通常不需要拥有和父智能体完全一样的能力。

本实现用静态字段定义了子智能体的工具集和分发表：

```java
// 子 Agent 的 4 个工具（无 task，防止递归生成子 Agent）
private static final List<Tool> CHILD_TOOLS = List.of(
    /* bash, read_file, write_file, edit_file */
);

// 子 Agent 的工具分发表
private static final Map<String, Function<Map<String, Object>, String>> CHILD_HANDLERS = ...;
```

关键设计：没有 `task` 工具，防止无限递归。

### 第四步：只把结果带回父智能体

子智能体做完事后，不把全部内部历史写回去，而是返回一段总结。

```java
// 从最后一轮回复中提取纯文本摘要
List<String> texts = new ArrayList<>();
for (ContentBlock block : lastResponse.content()) {
    block.text().ifPresent(textBlock -> texts.add(textBlock.text()));
}
return texts.isEmpty() ? "(no summary)" : String.join("", texts);
```

子智能体的整个上下文到这里就被丢弃了。只有这段文本摘要会返回给父智能体。

## 这一章最关键的数据结构

如果你只记一个结构，就记这个：

```java
// 子 Agent 的核心配置（分散在静态字段中）
CHILD_TOOLS     // List<Tool>       —— 子 Agent 可以调用哪些工具
CHILD_HANDLERS  // Map<String, Function> —— 工具名到处理函数的映射
SUBAGENT_SYSTEM // String           —— 子 Agent 的系统提示词
SUBAGENT_MAX_ROUNDS = 30            —— 防止子 Agent 无限跑
```

解释一下：

- `CHILD_TOOLS`：子智能体可以调用哪些工具（4 个基础工具，没有 `task`）
- `CHILD_HANDLERS`：这些工具到底对应哪些处理函数
- `SUBAGENT_SYSTEM`：子智能体的独立系统提示词
- `SUBAGENT_MAX_ROUNDS`：防止子智能体无限跑

这就是最小子智能体的骨架。

## 为什么它真的有用

### 用处 1：给父上下文减负

局部任务的中间噪声不会全都留在主对话里。

### 用处 2：让任务描述更清楚

一个子智能体接到的 prompt 可以非常聚焦：

- "读完这几个文件，给我一句总结"
- "检查这个目录里有没有测试"
- "对这个函数写一个最小修复"

### 用处 3：让后面的多 agent 协作有基础

你可以把子智能体理解成多 agent 系统的最小起点。

先把一次性子任务外包做明白，后面再升级到长期 teammate、任务认领、团队协议，会顺很多。

## 从 0 到 1 的实现顺序

推荐按这个顺序写：

### 版本 1：空白上下文子智能体

先只实现：

- 一个 `task` 工具
- 一个 `runSubagent(client, model, prompt)` 方法
- 子智能体自己的 `messages`（全新 `MessageCreateParams.Builder`）
- 子智能体最后返回摘要

这已经够了。

### 版本 2：限制工具集

给子智能体一个更小、更安全的工具集。

比如：

- 允许 `read_file`
- 允许 `edit_file`
- 允许 `bash`
- 不允许 `task`（防止递归）

本实现已经做到了这一点：`CHILD_TOOLS` 只包含 4 个基础工具。

### 版本 3：加入最大轮数和失败保护

至少补两个保护：

- 最多跑 30 轮（`SUBAGENT_MAX_ROUNDS`）
- 工具出错时返回错误信息而不是崩溃

### 版本 4：再考虑 fork

只有当你已经稳定跑通前面三步，才考虑 fork。

## 什么是 fork

前面的最小实现是：

- 子智能体从空白上下文开始

这叫最朴素的子智能体。

但有时一个子任务必须知道父智能体之前在聊什么。

例如：

> "基于我们刚才已经讨论出来的方案，去补测试。"

这时可以用 `fork`：

- 不是从空白 `messages` 开始
- 而是先复制父智能体的已有上下文，再追加子任务 prompt

```java
// fork 的概念（本实现暂未包含）
var subBuilder = MessageCreateParams.builder()
    .model(model)
    .system(SUBAGENT_SYSTEM);
// 复制父 Agent 的已有消息历史，而不是从零开始
for (var msg : parentMessages) {
    subBuilder.addMessage(msg);
}
subBuilder.addUserMessage(prompt);
```

这就是 fork 的本质：

**继承上下文，而不是重头开始。**

## 初学者最容易踩的坑

### 坑 1：把子智能体当成"为了炫技的并发"

子智能体首先是为了解决上下文问题，不是为了展示"我有很多 agent"。

### 坑 2：把父历史全部原样灌回去

如果你最后又把子智能体全量历史粘回父对话，那隔离价值就几乎没了。

### 坑 3：一上来就做特别复杂的角色系统

比如一开始就加：

- explorer
- reviewer
- planner
- tester
- implementer

这些都可以做，但不应该先做。

先把"一个干净上下文的子任务执行器"做对，后面角色化只是在它上面再包一层。

### 坑 4：忘记给子智能体设置停止条件

如果没有：

- 最大轮数（本实现是 30 轮）
- 异常处理
- 工具过滤（本实现通过 `CHILD_TOOLS` 过滤掉了 `task`）

子智能体很容易无限转。

## 教学边界

这章要先打牢的，不是"多 agent 很高级"，而是：

**子智能体首先是一个上下文边界。**

所以教学版先停在这里就够了：

- 一次性子任务就够
- 摘要返回就够
- 新 `messages` + 工具过滤就够

不要提前把 `fork`、后台运行、transcript 持久化、worktree 绑定一起塞进来。

真正该守住的顺序仍然是：

**先做隔离，再做高级化。**

## 和后续章节的关系

- `s04` 解决的是"局部任务的上下文隔离"
- `s15-s17` 解决的是"多个长期角色如何协作"
- `s18` 解决的是"多个执行者如何在文件系统层面隔离"

它们不是重复关系，而是递进关系。

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S04Subagent"
```

### 案例 1：文件探索（基础子智能体委派）

> 让模型用 `task` 工具把探索任务交给子智能体，观察上下文隔离效果。

```
帮我看看当前目录下有哪些 .java 文件，每个文件大概做什么
```

观察要点：
- 日志中出现 `> task (xxx):` —— 说明父智能体把探索任务委派给了子智能体
- 子智能体在自己的上下文里多次调用 `read_file` / `bash`，但这些中间步骤不会出现在父智能体的对话里
- 父智能体只收到一段简洁摘要，然后基于摘要直接回答你

### 案例 2：代码搜索 + 总结（子智能体多轮工具调用）

> 一个需要多次读文件才能回答的问题，验证子智能体可以在独立上下文里多轮工作。

先创建几个文件让子智能体探索：

```
帮我统计一下 src/main/java/com/example/agent/sessions/ 下每个 Java 文件有多少行代码，找出最长的那个文件，总结它的主要功能
```

观察要点：
- 子智能体可能先 `bash` 列出文件，再逐个 `read_file` 读取——这是多轮工具调用
- 父智能体的 `messages` 只增加了：一条 `task` 调用 + 一段摘要结果
- 对比如果不用子智能体，所有文件内容都会直接塞进父对话

### 案例 3：多步编写任务（子智能体独立完成创作）

> 让子智能体独立完成一个需要多步文件操作的任务，父智能体只负责验收摘要。

```
用 task 工具创建一个子任务：在当前目录下创建一个 temp_demo 文件夹，里面写一个 Fibonacci.java，实现递归斐波那契，编译运行验证输出前 10 个数
```

观察要点：
- 子智能体在独立上下文中完成：创建目录 → 写文件 → 编译 → 运行 → 总结
- 父智能体全程只看到一行 `> task` 日志和一段摘要
- 可以用 `bash` 验证文件确实被创建了（说明子智能体共享了文件系统）

### 案例 4：连续追问（验证父上下文保持干净）

> 先让父智能体做一次委派，然后问一个完全不相关的问题，验证父上下文没有被中间步骤污染。

```
用 task 帮我看看 pom.xml 里用了哪些依赖
```

等它回答后，紧接着问：

```
今天是几号？
```

观察要点：
- 第一个问题触发了 `task` 子智能体，摘要返回后父对话里只有摘要
- 第二个问题不需要任何工具调用，模型直接回答——说明父上下文没有被 pom.xml 的内容撑满
- 如果连续追问多个探索类问题，父上下文依然保持简洁（每次只多一段摘要）

## 这章学完后你应该能回答

- 为什么大任务不应该总塞在一个 `messages` 里？
- 子智能体最小版为什么只需要独立上下文和摘要返回？
- fork 是什么，为什么它不该成为第一步？
- 为什么子智能体的第一价值是"减噪"，而不是"炫多 agent"？

## 一句话记住

**子智能体的核心，不是多一个角色，而是多一个干净上下文。**
