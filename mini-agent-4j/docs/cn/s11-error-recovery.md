# s11：错误恢复

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > [ s11 ] s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *"一个健壮的 Agent 会自动恢复，而不是崩溃退出。"* —— 三条恢复路径让 Agent 顽强运行。

## 课程目标

理解如何为 Agent 构建错误恢复机制。LLM 调用可能因多种原因失败：输出截断、上下文过长、网络超时。一个健壮的 Agent 应该自动恢复，而不是崩溃退出。

## 问题

Agent 在运行中会遇到三类错误：

1. **max_tokens**：模型输出被截断，回复不完整
2. **prompt_too_long**：对话历史超过上下文窗口
3. **网络错误**：API 连接超时或限速

如果 Agent 直接崩溃，用户的工作就中断了。需要自动恢复机制让 Agent 继续运行。

## 方案

三条恢复路径，按优先级匹配：

```
    LLM 响应
        |
        v
   [检查 stop_reason]
        |
        +-- max_tokens ──→ [策略 1: 注入续接消息]
        |                    "Continue from where you stopped."
        |                    最多重试 3 次
        |
        +-- API 异常 ──→ [检查错误类型]
        |                   |
        |                   +-- prompt_too_long → [策略 2: autoCompact]
        |                   |   调用 LLM 生成摘要
        |                   |   用摘要替换历史
        |                   |
        |                   +-- 连接/限速错误 → [策略 3: 指数退避重试]
        |                       base * 2^attempt + random(0,1)
        |                       最多 3 次重试
        |
        +-- end_turn ──→ [正常退出]
```

## 核心概念

### 策略 1：max_tokens 恢复

当 LLM 输出被截断时，注入续接消息让模型继续：

```java
private static final String CONTINUATION_MESSAGE =
    "Output limit hit. Continue directly from where you stopped -- "
    + "no recap, no repetition. Pick up mid-sentence if needed.";

if (isMaxTokens) {
    maxOutputRecoveryCount++;
    if (maxOutputRecoveryCount <= MAX_RECOVERY_ATTEMPTS) {
        paramsHolder[0].addUserMessage(CONTINUATION_MESSAGE);
        continue; // 注入续接消息后重试
    }
}
```

### 策略 2：autoCompact 上下文压缩

当对话历史过长时，调用 LLM 生成摘要并替换整个历史：

```java
private static MessageCreateParams.Builder autoCompact(...) {
    // 1. 将对话日志拼接为文本（截取最后 80000 字符）
    // 2. 调用 LLM 生成摘要（任务概览、当前状态、关键决策、剩余步骤）
    // 3. 用摘要重建 paramsBuilder
    // 4. 重置 token 估算计数器

    String prompt = "Summarize this conversation for continuity. Include:\n"
        + "1) Task overview and success criteria\n"
        + "2) Current state: completed work, files touched\n"
        + "3) Key decisions and failed approaches\n"
        + "4) Remaining next steps";
}
```

### 策略 3：指数退避重试

网络错误时带抖动的指数退避重试：

```java
private static double backoffDelay(int attempt) {
    double delay = Math.min(BACKOFF_BASE_DELAY * Math.pow(2, attempt), BACKOFF_MAX_DELAY);
    double jitter = ThreadLocalRandom.current().nextDouble(0, 1);
    return delay + jitter; // 抖动防止惊群效应
}
```

参数配置：

| 参数                | 值       | 说明               |
|---------------------|----------|---------------------|
| MAX_RECOVERY_ATTEMPTS | 3      | 最大恢复尝试次数     |
| BACKOFF_BASE_DELAY    | 1.0s   | 退避基础延迟         |
| BACKOFF_MAX_DELAY     | 30.0s  | 退避最大延迟         |
| TOKEN_THRESHOLD       | 50000  | 主动压缩 token 阈值  |

### 主动 auto-compact 检查

除了被动响应错误外，每轮工具执行后主动检查 token 估算值：

```java
// 主动 auto-compact 检查
if (isOverTokenThreshold(tokenEstimate)) {
    System.out.println("[Recovery] Token estimate exceeds threshold. Auto-compacting...");
    paramsHolder[0] = autoCompact(client, model, paramsHolder[0], ...);
}
```

## 关键代码片段

带重试的 API 调用包裹在 Agent 循环中：

```java
for (int attempt = 0; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
    try {
        response = client.messages().create(paramsHolder[0].build());
        break; // 成功
    } catch (Exception e) {
        if (isPromptTooLong(e)) {
            // 策略 2：压缩并重试
            paramsHolder[0] = autoCompact(...);
            continue;
        }
        // 策略 3：指数退避重试
        double delay = backoffDelay(attempt);
        Thread.sleep((long)(delay * 1000));
        continue;
    }
}
```

## 变更对比

| 组件          | S10             | S11                                 |
|---------------|-----------------|--------------------------------------|
| max_tokens 恢复 | （无）        | 注入续接消息，最多 3 次              |
| prompt_too_long | （无）        | autoCompact LLM 摘要压缩            |
| 网络重试       | （无）          | 指数退避 + 抖动，最多 3 次           |
| token 估算     | （无）          | 粗略估算（字符数 / 4）              |
| 主动压缩       | （无）          | 超 token 阈值自动触发               |
| Agent 循环     | 标准循环        | 带重试 + 恢复的增强循环             |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S11ErrorRecovery"
```

1. 输入一个需要大量输出的任务（如"列出当前目录所有文件的详细内容"），观察 max_tokens 恢复
2. 在一个有很多文件的目录运行，观察 token 估算和主动压缩
3. 断开网络连接后输入指令，观察退避重试日志

## 要点总结

1. 三条恢复路径：max_tokens 续接、prompt_too_long 压缩、网络错误退避
2. 恢复优先级：max_tokens > prompt_too_long > 连接错误 > 优雅失败
3. 指数退避 + 抖动防止多个客户端同时重试（惊群效应）
4. autoCompact 调用 LLM 生成摘要，保留关键信息丢弃历史细节
5. 主动 token 检查在错误发生前预防性压缩
