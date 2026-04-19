# s11: Error Recovery (错误恢复)

`s00 > s01 > s02 > s03 > s04 > s05 > s06 > s07 > s08 > s09 > s10 > [ s11 ] > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *错误不是例外，而是主循环必须预留出来的一条正常分支。*

## 这一章要解决什么问题

到了 `s10`，你的 agent 已经有了：

- 主循环
- 工具调用
- 规划
- 上下文压缩
- 权限、hook、memory、system prompt

这时候系统已经不再是一个"只会聊天"的 demo，而是一个真的在做事的程序。

问题也随之出现：

- 模型输出写到一半被截断
- 上下文太长，请求直接失败
- 网络暂时抖动，API 超时或限流

如果没有恢复机制，主循环会在第一个错误上直接停住。
这对初学者很危险，因为他们会误以为"agent 不稳定是模型的问题"。

实际上，很多失败并不是"任务真的失败了"，而只是：

**这一轮需要换一种继续方式。**

所以这一章的目标只有一个：

**把"报错就崩"升级成"先判断错误类型，再选择恢复路径"。**

## 建议联读

- 如果你开始分不清"为什么这一轮还在继续"，先回 [`s00c-query-transition-model.md`](./s00c-query-transition-model.md)，重新确认 transition reason 为什么是独立状态。
- 如果你在恢复逻辑里又把上下文压缩和错误恢复混成一团，建议顺手回看 [`s06-context-compact.md`](./s06-context-compact.md)，区分"为了缩上下文而压缩"和"因为失败而恢复"。
- 如果你准备继续往 `s12` 走，建议把 [`data-structures.md`](./data-structures.md) 放在旁边，因为后面任务系统会在"恢复状态之外"再引入新的 durable work 状态。

## 先解释几个名词

### 什么叫恢复

恢复，不是把所有错误都藏起来。

恢复的意思是：

- 先判断这是不是临时问题
- 如果是，就尝试一个有限次数的补救动作
- 如果补救失败，再把失败明确告诉用户

### 什么叫重试预算

重试预算，就是"最多试几次"。

比如：

- 续写最多 3 次
- 网络重连最多 3 次

如果没有这个预算，程序就可能无限循环。

在 `S11ErrorRecovery.java` 中，重试预算是一个常量：

```java
private static final int MAX_RECOVERY_ATTEMPTS = 3;
```

### 什么叫状态机

状态机这个词听起来很大，其实意思很简单：

> 一个东西会在几个明确状态之间按规则切换。

在这一章里，主循环就从"普通执行"变成了：

- 正常执行
- 续写恢复
- 压缩恢复
- 退避重试
- 最终失败

## 最小心智模型

不要把错误恢复想得太神秘。

教学版只需要先区分 3 类问题：

```text
1. 输出被截断
   模型还没说完，但 token 用完了

2. 上下文太长
   请求装不进模型窗口了

3. 临时连接失败
   网络、超时、限流、服务抖动
```

对应 3 条恢复路径：

```text
LLM call
  |
  +-- stop_reason == "max_tokens"
  |      -> 注入续写提示
  |      -> 再试一次
  |
  +-- prompt too long
  |      -> 压缩旧上下文
  |      -> 再试一次
  |
  +-- timeout / rate limit / transient API error
         -> 等一会儿
         -> 再试一次
```

这就是最小但正确的恢复模型。

## 关键数据结构

### 1. 恢复状态

Java 实现中，恢复状态通过计数器跟踪：

```java
int maxOutputRecoveryCount = 0;        // max_tokens 恢复计数
// prompt_too_long 和网络错误使用同一个 for 循环的 attempt 计数
```

它的作用不是"记录一切"，而是：

- 防止无限重试
- 让每种恢复路径各算各的次数

### 2. 恢复决策

恢复决策隐含在条件判断中，但可以概念化为：

```java
// 概念上的恢复决策类型：
// "continue"  -- 输出截断，注入续写
// "compact"   -- prompt 过长，压缩后重试
// "backoff"   -- 网络抖动，等待后重试
// "fail"      -- 所有重试耗尽，优雅失败
```

把"错误长什么样"和"接下来怎么做"分开，会更清楚。

### 3. 续写提示

```java
private static final String CONTINUATION_MESSAGE =
    "Output limit hit. Continue directly from where you stopped -- "
    + "no recap, no repetition. Pick up mid-sentence if needed.";
```

这条提示非常重要。

因为如果你只说"继续"，模型经常会：

- 重新总结
- 重新开头
- 重复已经输出过的内容

## 三条恢复路径分别在补什么洞

### 路径 1：输出被截断时，做续写

这个问题的本质不是"模型不会"，而是"这一轮输出空间不够"。

所以最小补法是：

1. 追加一条续写消息
2. 告诉模型不要重来，不要重复
3. 让主循环继续

```java
if (isMaxTokens) {
    maxOutputRecoveryCount++;
    if (maxOutputRecoveryCount <= MAX_RECOVERY_ATTEMPTS) {
        paramsHolder[0].addUserMessage(CONTINUATION_MESSAGE);
        continue; // 注入续接消息后重试
    } else {
        System.err.println("[Error] max_tokens recovery exhausted.");
        return;
    }
}
```

### 路径 2：上下文太长时，先压缩再重试

这里要先明确一点：

压缩不是"把历史删掉"，而是：

**把旧对话从原文，变成一份仍然可继续工作的摘要。**

最小压缩结果建议至少保留：

- 当前任务是什么
- 已经做了什么
- 关键决定是什么
- 下一步准备做什么

```java
private static MessageCreateParams.Builder autoCompact(
        AnthropicClient client, String model,
        MessageCreateParams.Builder paramsBuilder,
        String systemPrompt, List<Tool> tools,
        List<String> conversationLog,
        long[] tokenEstimate) {

    // 1. 将对话日志拼接为文本（截取最后 80000 字符）
    // 2. 调用 LLM 生成摘要
    // 3. 用摘要重建 paramsBuilder
    // 4. 重置 token 估算计数器
}
```

除了被动响应错误外，每轮工具执行后还会主动检查 token 估算值：

```java
if (isOverTokenThreshold(tokenEstimate)) {
    paramsHolder[0] = autoCompact(...);
}
```

### 路径 3：连接抖动时，退避重试

"退避"这个词的意思是：

> 别立刻再打一次，而是等一小会儿再试。

为什么要等？

因为这类错误往往是临时拥堵：

- 刚超时
- 刚限流
- 服务器刚好抖了一下

如果你瞬间连续重打，只会更容易失败。

```java
private static double backoffDelay(int attempt) {
    double delay = Math.min(BACKOFF_BASE_DELAY * Math.pow(2, attempt), BACKOFF_MAX_DELAY);
    double jitter = ThreadLocalRandom.current().nextDouble(0, 1);
    return delay + jitter; // 抖动防止惊群效应
}
```

参数配置：

| 参数 | 值 | 说明 |
|---|---|---|
| MAX_RECOVERY_ATTEMPTS | 3 | 最大恢复尝试次数 |
| BACKOFF_BASE_DELAY | 1.0s | 退避基础延迟 |
| BACKOFF_MAX_DELAY | 30.0s | 退避最大延迟 |
| TOKEN_THRESHOLD | 50000 | 主动压缩 token 阈值 |

## 如何接到主循环里

最干净的接法，是把恢复逻辑放在两个位置：

### 位置 1：模型调用外层

负责处理：

- API 报错
- 网络错误
- 超时

```java
for (int attempt = 0; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
    try {
        response = client.messages().create(paramsHolder[0].build());
        break; // 成功，跳出重试循环
    } catch (Exception e) {
        if (isPromptTooLong(e)) {
            paramsHolder[0] = autoCompact(...);
            continue; // 压缩后重试
        }
        // 网络错误 -> 指数退避重试
        double delay = backoffDelay(attempt);
        Thread.sleep((long)(delay * 1000));
        continue;
    }
}
```

### 位置 2：拿到 response 以后

负责处理：

- `stopReason == MAX_TOKENS`
- 正常的 `TOOL_USE`
- 正常的结束

也就是说，主循环现在不只是"调模型 -> 执行工具"，而是：

```text
1. 调模型
2. 如果调用报错，判断是否可以恢复
3. 如果拿到响应，判断是否被截断
4. 如果需要恢复，就修改 messages 或等待
5. 如果不需要恢复，再进入正常工具分支
```

## 初学者最容易犯的错

### 1. 把所有错误都当成一种错误

这样会导致：

- 该续写的去压缩
- 该等待的去重试
- 该失败的却无限拖延

### 2. 没有重试预算

没有预算，主循环就可能永远卡在"继续""继续""继续"。

### 3. 续写提示写得太模糊

只写一个"continue"通常不够。
你要明确告诉模型：

- 不要重复
- 不要重新总结
- 直接从中断点接着写

### 4. 压缩后没有告诉模型"这是续场"

如果压缩后只给一份摘要，不告诉模型"这是前文摘要"，模型很可能重新向用户提问。

### 5. 恢复过程完全没有日志

教学系统最好打印类似：

- `[Recovery] max_tokens hit`
- `[Recovery] Prompt too long. Compacting...`
- `[Recovery] Network error. Retrying in Xs`

这样读者才看得见主循环到底做了什么。

## 教学边界

这一章先把 3 条最小恢复路径讲稳就够了：

- 输出截断后续写
- 上下文过长后压缩再试
- 请求抖动后退避重试

对教学主线来说，重点不是把所有"为什么继续下一轮"的原因一次讲全，而是先让读者明白：

**恢复不是简单 try/except，而是系统知道该怎么续下去。**

更大的 query 续行模型、预算续行、hook 介入这些内容，应该放回控制平面的桥接文档里看，而不是抢掉这章主线。

## 这一章和前后章节怎么衔接

- `s06` 讲的是"什么时候该压缩"
- `s10` 讲的是"系统提示词怎么组装"
- `s11` 讲的是"当执行失败时，主循环怎么续下去"
- `s12` 开始，恢复机制会保护更长、更复杂的任务流

所以 `s11` 的位置非常关键。

它不是外围小功能，而是：

**把 agent 从"能跑"推进到"遇到问题也能继续跑"。**

## 试一试

### 启动

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S11ErrorRecovery"
```

启动时观察 dim 输出：`Recovery attempts: 3, backoff base: 1.0s, max: 30.0s, token threshold: 50000`。

### 案例 1：正常操作基线（无恢复触发）

> 执行一个简单任务，验证恢复系统不干扰正常工作流。

```
帮我列出当前目录下的文件
```

观察要点：
- 工具正常执行，输出 `> bash:` 或 `> read_file:` 日志
- **没有**黄色 `[Recovery]` 日志出现 —— 正常操作不触发恢复路径
- 主循环行为与 S02 完全一致，恢复逻辑是透明旁路

### 案例 2：长输出触发 max_tokens 恢复（策略 1）

> 请求模型生成一段特别长的内容，观察输出被截断后的续写恢复。

```
请帮我逐行详细解释 pom.xml 的每一个依赖，格式为：依赖名 → 用途 → 为什么需要它。输出要尽可能详细。
```

观察要点：
- 如果模型输出被截断（`stopReason == MAX_TOKENS`），日志出现 `[Recovery] max_tokens hit (1/3). Injecting continuation...`
- 系统自动注入续写消息 `"Output limit hit. Continue directly..."`，模型从中断点继续输出
- 计数器在成功恢复后重置为 0
- 最多续写 3 次，超过后打印 `[Error] max_tokens recovery exhausted`

### 案例 3：Token 累积触发主动 auto-compact（策略 2 预防式）

> 连续读取多个文件，累积 token 估算值超过 50000 阈值，观察主动压缩介入。

```
依次读取 src/main/java/com/example/agent/sessions/ 下每个 Java 文件的前 50 行，帮我统计每个 Session 的功能
```

观察要点：
- 前几次 read_file 正常执行，每次工具输出累加到 tokenEstimate
- 当估算值超过 50000 时，日志自动出现 `[Recovery] Token estimate exceeds threshold. Auto-compacting...`
- 这是**预防式**压缩——不等 prompt 真的过长报错，而是提前压缩
- 压缩后 agent 继续工作，不会丢失"已读了哪些文件"的上下文

### 案例 4：理解恢复流水线的完整结构

> 通过构造不同场景，理解三条恢复路径的优先级和触发条件。

先验证策略 2 的**被动式**触发（如果 API 返回 prompt 过长错误）：

```
读取 pom.xml 的全部内容，然后重复告诉我 50 遍它的内容
```

观察要点：
- 如果 API 返回 prompt 过长错误，日志出现 `[Recovery] Prompt too long. Compacting... (attempt N)`
- 压缩后自动重试，不需要用户干预
- 与案例 3 的区别：案例 3 是预防式（超阈值就压缩），这里是被动式（API 拒绝后才压缩）

如果网络出现临时错误（策略 3）：

观察要点：
- 日志出现 `[Recovery] Network error: ... Retrying in Xs (attempt N/3)` 或 `[Recovery] API error: ... Retrying in Xs`
- 退避时间指数增长：约 1s → 2s → 4s（加随机抖动）
- 这是策略 3b：非 prompt_too_long 的 API 错误（如 rate limit、server error）也会退避重试

三条路径的优先级总结：
```
1. prompt_too_long → 立即压缩，重试（不等待）
2. 网络错误/API 错误 → 退避等待，重试
3. 所有重试耗尽 → 打印错误，优雅退出
```

读这一章时，你真正要记住的不是某个具体异常名，而是这条主线：

**错误先分类，恢复再执行，失败最后才暴露给用户。**
