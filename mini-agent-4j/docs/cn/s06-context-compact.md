# s06: Context Compact (上下文压缩)

`s00 > s01 > s02 > s03 > s04 > s05 > [ s06 ] > s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *上下文不是越多越好，而是要把"仍然有用的部分"留在活跃工作面里。*

## 这一章要解决什么问题

到了 `s05`，agent 已经会：

- 读写文件
- 规划步骤
- 派子 agent
- 按需加载 skill

也正因为它会做的事情更多了，上下文会越来越快膨胀：

- 读一个大文件，会塞进很多文本
- 跑一条长命令，会得到大段输出
- 多轮任务推进后，旧结果会越来越多

如果没有压缩机制，很快就会出现这些问题：

1. 模型注意力被旧结果淹没
2. API 请求越来越重，越来越贵
3. 最终直接撞上上下文上限，任务中断

所以这一章真正要解决的是：

**怎样在不丢掉主线连续性的前提下，把活跃上下文重新腾出空间。**

## 先解释几个名词

### 什么是上下文窗口

你可以把上下文窗口理解成：

> 模型这一轮真正能一起看到的输入容量。

它不是无限的。

### 什么是活跃上下文

并不是历史上出现过的所有内容，都必须一直留在窗口里。

活跃上下文更像：

> 当前这几轮继续工作时，最值得模型马上看到的那一部分。

### 什么是压缩

这里的压缩，不是 ZIP 压缩文件。

它的意思是：

> 用更短的表示方式，保留继续工作真正需要的信息。

例如：

- 大输出只保留预览，全文写到磁盘
- 很久以前的工具结果改成占位提示
- 整段长历史总结成一份摘要

## 最小心智模型

这一章建议你先记三层，不要一上来记八层十层：

```text
第 1 层：大结果不直接塞进上下文
  -> 写到磁盘，只留预览

第 2 层：旧结果不一直原样保留
  -> 替换成简短占位

第 3 层：整体历史太长时
  -> 生成一份连续性摘要
```

可以画成这样：

```text
tool output
   |
   +-- 太大 -----------------> 保存到磁盘 + 留预览
   |
   v
messages
   |
   +-- 太旧 -----------------> 替换成占位提示
   |
   v
if whole context still too large:
   |
   v
compact history -> summary
```

手动触发 `compact` 工具，本质上也是走第 3 层。

### Java 实现的取舍

Python 原版实现了完整三层。Java 实现跳过了第 2 层（microCompact），只实现第 1 层和第 3 层。原因在下面"关键数据结构"中详细解释。

## 关键数据结构

### 1. Persisted Output Marker

当工具输出太大时，不要把全文强塞进当前对话。

最小标记长这样：

```text
<persisted-output>
Full output saved to: .task_outputs/tool-results/abc123.txt
Preview:
...
</persisted-output>
```

这个结构表达的是：

- 全文没有丢
- 只是搬去了磁盘
- 当前上下文里只保留一个足够让模型继续判断的预览

Java 实现中的阈值：

```java
private static final int PERSIST_THRESHOLD = 30000;  // 30KB 以上触发
private static final int PREVIEW_CHARS = 2000;        // 预览保留 2000 字符
```

### 2. CompactState

Java 实现通过两个可变状态变量维护压缩状态：

```java
// token 估算：用数组包装使其在方法间可变（类似 Python 的可变引用）
long[] tokenEstimate = {0};

// 对话日志：用于压缩时生成摘要
List<String> conversationLog = new ArrayList<>();
```

- `tokenEstimate[0]`：当前对话的粗略 token 估算（字符数 / 4）
- `conversationLog`：对话历史记录，压缩时传给 LLM 生成摘要

### 3. Micro-Compact Boundary

教学版可以先设一条简单规则：

```text
只保留最近 3 个工具结果的完整内容
更旧的改成占位提示
```

**Java 实现中这一层被有意跳过。** 原因是 Python 版使用可变 dict，可以就地修改旧的 `tool_result` 内容。而 Java SDK 的 `MessageParam` 是不可变的 -- 你无法修改已经加入 `paramsBuilder` 的消息。虽然可以通过重建 builder 实现，但这会显著增加代码复杂度，而且 `s_full.py` 的实际做法也是只做 persist + full compact。所以 Java 版为了教学简洁性，只实现第 1 层和第 3 层。

## 压缩后，真正要保住什么

这是这章最容易讲虚的地方。

压缩不是"把历史缩短"这么简单。

真正重要的是：

**让模型还能继续接着干活。**

所以一份合格的压缩结果，至少要保住下面这些东西：

1. 当前任务目标
2. 已完成的关键动作
3. 已修改或重点查看过的文件
4. 关键决定与约束
5. 下一步应该做什么

如果这些没有保住，那压缩虽然腾出了空间，却打断了工作连续性。

## 最小实现

### 第一步：大工具结果先写磁盘

```java
private static String persistLargeOutput(String toolUseId, String output) {
    if (output.length() <= PERSIST_THRESHOLD) {
        return output;   // 不超过阈值，原样返回
    }

    // 创建持久化目录
    Files.createDirectories(TOOL_RESULTS_DIR);

    // 保存完整输出到文件
    Path storedPath = TOOL_RESULTS_DIR.resolve(toolUseId + ".txt");
    Files.writeString(storedPath, output);

    // 构建预览标记
    String preview = output.substring(0, Math.min(output.length(), PREVIEW_CHARS));
    return "<persisted-output>\n"
            + "Full output saved to: " + relativePath + "\n"
            + "Preview:\n" + preview + "\n"
            + "</persisted-output>";
}
```

这一步的关键思想是：

> 让模型知道"发生了什么"，但不强迫它一直背着整份原始大输出。

### 第二步：旧工具结果做微压缩

Python 版的做法：

```python
def micro_compact(messages: list) -> list:
    tool_results = collect_tool_results(messages)
    for result in tool_results[:-3]:
        result["content"] = "[Earlier tool result omitted for brevity]"
    return messages
```

Java 版跳过此步（见上方"Micro-Compact Boundary"的解释）。

### 第三步：整体历史过长时，做一次完整压缩

```java
private static MessageCreateParams.Builder autoCompact(
        AnthropicClient client, String model,
        MessageCreateParams.Builder paramsBuilder,
        String systemPrompt, List<Tool> tools,
        List<String> conversationLog,
        long[] tokenEstimate, String focus) {

    // 1. 保存 transcript 到 .transcripts/ 目录
    Path transcriptPath = writeTranscript(conversationLog);

    // 2. 调用 LLM 生成摘要
    String summary = summarizeHistory(client, model, conversationLog);

    // 3. 重建 paramsBuilder，只包含摘要消息
    MessageCreateParams.Builder newBuilder = MessageCreateParams.builder()
            .model(model).maxTokens(MAX_TOKENS).system(systemPrompt);
    for (Tool tool : tools) newBuilder.addTool(tool);

    String compactedMessage = "This conversation was compacted so the agent can continue working.\n\n"
            + summary;
    newBuilder.addUserMessage(compactedMessage);
    newBuilder.addAssistantMessage("Understood. I have the context summary. Ready to continue.");

    // 4. 重置计数器
    tokenEstimate[0] = compactedMessage.length() / 4;
    return newBuilder;
}
```

注意：这里没有单独的 `ContextCompressor` 或 `TokenEstimator` 类。所有方法都内联在 `S06ContextCompact` 中，保持单文件自包含。

### 第四步：在主循环里接入压缩

```java
private static void agentLoop(..., long[] tokenEstimate) {
    while (true) {
        // 每轮开始前检查 token 估算值
        if (shouldCompact(tokenEstimate[0])) {
            paramsHolder[0] = autoCompact(client, model, paramsHolder[0],
                    systemPrompt, tools, conversationLog, tokenEstimate, null);
        }

        Message response = client.messages().create(paramsHolder[0].build());
        paramsHolder[0].addMessage(response);

        // ... 执行工具，累加 tokenEstimate ...
    }
}
```

压缩触发阈值：

```java
private static final int CONTEXT_LIMIT = 12000;  // 估算 token 数
// 12000 tokens * 4 chars/token ~ 48000 字符，与 Python 的 50000 字符阈值等价
```

### 第五步：手动压缩和自动压缩复用同一条机制

模型可以调用 `compact` 工具主动触发压缩。Java 实现中 `compact` 工具没有单独的处理器 -- 它在 `agentLoop` 内部被拦截：

```java
if ("compact".equals(toolName)) {
    manualCompact = true;
    compactFocus = (String) input.get("focus");
    output = "Compressing conversation...";
}

// ... 工具结果追加后 ...

if (manualCompact) {
    paramsHolder[0] = autoCompact(client, model, paramsHolder[0],
            systemPrompt, tools, conversationLog, tokenEstimate, compactFocus);
}
```

教学版里，`compact` 工具不需要重新发明另一套逻辑。

它只需要表达：

> 用户或模型现在主动要求执行一次完整压缩。

## 它如何接到主循环里

从这一章开始，主循环不再只是：

- 收消息
- 调模型
- 跑工具

它还多了一个很关键的责任：

- 管理活跃上下文的预算

也就是说，agent loop 现在开始同时维护两件事：

```text
任务推进
上下文预算
```

这一步非常重要，因为后面的很多机制都会和它联动：

- `s09` memory 决定什么信息值得长期保存
- `s10` prompt pipeline 决定哪些块应该重新注入
- `s11` error recovery 会处理压缩不足时的恢复分支

## 初学者最容易犯的错

### 1. 以为压缩等于删除

不是。

更准确地说，是把"不必常驻活跃上下文"的内容换一种表示。

### 2. 只在撞到上限后才临时乱补

更好的做法是从一开始就有三层思路：

- 大结果先落盘
- 旧结果先缩短
- 整体过长再摘要

### 3. 摘要只写成一句空话

如果摘要没有保住文件、决定、下一步，它对继续工作没有帮助。

### 4. 把压缩和 memory 混成一类

压缩解决的是：

- 当前会话太长了怎么办

memory 解决的是：

- 哪些信息跨会话仍然值得保留

### 5. 一上来就给初学者讲过多产品化层级

教学主线先讲清最小正确模型，比堆很多层名词更重要。

## 教学边界

这章不要滑成"所有产品化压缩技巧大全"。

教学版只需要讲清三件事：

1. 什么该留在活跃上下文里
2. 什么该搬到磁盘或占位标记里
3. 完整压缩后，哪些连续性信息一定不能丢

这已经足够建立稳定心智：

**压缩不是删历史，而是把细节搬走，好让系统继续工作。**

如果读者已经能用 `persisted output + full compact` 保住长会话连续性，这章就已经够深了。

Java 版有意跳过 microCompact，这是与 Python 版的一个差异，但不影响核心心智模型的建立。

## 一句话记住

**上下文压缩的核心，不是尽量少字，而是让模型在更短的活跃上下文里，仍然保住继续工作的连续性。**

---

**运行**

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S06ContextCompact"
```

1. `读取 src/main/java 下所有 Java 文件并摘要项目`（生成大量上下文）
2. `现在用 compact 工具压缩对话`
3. `压缩后你还记得项目的哪些内容？`
