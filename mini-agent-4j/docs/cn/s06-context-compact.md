# s06：上下文压缩

`s01 > s02 > s03 > s04 > s05 > [ s06 ] | s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *"Agent 可以策略性地遗忘，然后继续无限工作。"* —— 没有什么真正丢失，只是移出了活跃上下文。
>
> **装置层**：压缩管线 —— 三层记忆管理。

## 问题

每次工具调用都增加 token。30 轮之后，对话可能达到 100,000 个 token。LLM 有上下文窗口限制，超过这个限制 API 会返回错误。你需要在不丢失关键信息的情况下压缩对话。

## 方案

```
Layer 1：microCompact（每轮，静默）
+---+---+---+---+---+---+      +---+---+---+---+---+---+
| r1| r2| r3| r4| r5| r6|  --> | [ ]| [ ]| [ ]| r4| r5| r6|  保留最近 3 个
+---+---+---+---+---+---+      +---+---+---+---+---+---+

Layer 2：autoCompact（当 tokens > 阈值）
+---+---+---+---+---+---+      +---------+---------+
| r1| r2| r3| r4| r5| r6|  --> |  摘要   | 新消息  |
| ...很长的历史...         |     | （LLM） |         |
+---+---+---+---+---+---+      +---------+---------+
         |                           ^
         v                           |
   .transcripts/              LLM 摘要
   保存到磁盘                  完整对话

Layer 3：manualCompact（模型调用 `compact` 工具）
  与 Layer 2 相同，但由模型按需触发。
```

三层协同工作：每轮微压缩、超阈值自动压缩、模型主动手动压缩。

## 原理

1. **Layer 1：microCompact** —— 每轮静默将旧的 `tool_result` 内容替换为占位符。只保留最近 3 个工具结果的完整内容。

```java
// ContextCompressor.microCompact()
// 旧：完整的 "ls -la" 输出（500 tokens）
// 新："[Previous: used bash]"
```

2. **Layer 2：autoCompact** —— 当 token 数超过 50,000 时，将完整对话保存到磁盘，然后让 LLM 摘要：

```java
ContextCompressor compressor = new ContextCompressor(client, model, transcriptDir);

// 1. 将完整对话保存到 .transcripts/session_TTT.jsonl
// 2. 调用 LLM："摘要这段对话，保留关键决策和文件状态"
// 3. 将所有消息替换为：[summary_message, new_user_message]
```

3. **Layer 3：manualCompact** —— 模型可以调用 `compact` 工具按需触发压缩：

```java
AgentLoop.defineTool("compact", "Trigger manual conversation compression.",
    Map.of("focus", Map.of("type", "string",
        "description", "What to preserve in the summary")),
    null)
```

4. **对话记录永不删除。** 它们以 JSONL 文件的形式保存在 `.transcripts/` 目录中。没有什么真正丢失 —— 只是从活跃上下文窗口移出去了。

5. **Token 估算**使用简单启发式：`chars / 4`。这是近似值，但足以用于阈值检测：

```java
// TokenEstimator
long estimate(String text) { return text.length() / 4; }
```

## 变更对比

| 组件          | s05                 | s06                               |
|---------------|---------------------|-----------------------------------|
| 工具          | 6 个                | +1：`compact`                     |
| 压缩          | （无）              | 三层管线                          |
| Token 追踪    | （无）              | `TokenEstimator`（chars/4）       |
| 磁盘存储      | （无）              | `.transcripts/` JSONL 文件        |
| 新增类        | `SkillLoader`       | `ContextCompressor`、`TokenEstimator` |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S06ContextCompact"
```

1. `读取 src/main/java 下所有 Java 文件并摘要项目`（生成大量上下文）
2. `现在用 compact 工具压缩对话`
3. `压缩后你还记得项目的哪些内容？`
