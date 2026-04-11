# s10a: Message & Prompt Pipeline (消息与提示词管道)

> 这篇桥接文档是 `s10` 的扩展。
> 它要补清一个很关键的心智：
>
> **system prompt 很重要，但它不是模型完整输入的全部。**

## 为什么要补这一篇

`s10`（`S10SystemPrompt.java`）已经把 system prompt 从"大字符串"升级成"可维护的组装流水线"，这一步非常重要。

但当系统开始长出更多输入来源时，还会继续往前走一步：

它会发现，真正送给模型的输入，不只包含：

- system prompt

还包含：

- 规范化后的 messages
- memory attachments
- hook 注入消息
- system reminder
- 当前轮次的动态上下文

也就是说，真正的输入更像一条完整管道：

**Prompt Pipeline，而不只是 Prompt Builder。**

## 先解释几个名词

### 什么是 prompt block

你可以把 `prompt block` 理解成：

> system prompt 内部的一段结构化片段。

例如：

- 核心身份说明
- 工具说明
- memory section
- AGENT.md section（mini-agent-4j 的配置指令链）

### 什么是 normalized message

`normalized message` 的意思是：

> 把不同来源、不同格式的消息整理成统一、稳定、可发给模型的消息形式。

为什么需要这一步？

因为系统里可能出现：

- 普通用户消息（`UserMessage`）
- assistant 回复（`AssistantMessage`）
- tool_result（`ToolResultBlock`）
- 系统提醒
- attachment 包裹消息

如果不先整理，模型输入层会越来越乱。

在 Anthropic Java SDK 中，这些消息最终都实现 `Message` 或 `Content` 接口：

```java
// Anthropic Java SDK 中的消息类型
UserMessage.of("...")                            // 用户消息
AssistantMessage.builder().content(...).build()   // 助手消息
ToolResultBlock.builder()                         // 工具结果
    .toolUseId("...")
    .content("...")
    .build()
```

### 什么是 system reminder

这在 `s10` 已经提到过。

它不是长期规则，而是：

> 只在当前轮或当前阶段临时追加的一小段系统信息。

## 最小心智模型

把完整输入先理解成下面这条流水线：

```text
多种输入来源
  |
  +-- system prompt blocks
  +-- messages
  +-- attachments
  +-- reminders
  |
  v
normalize
  |
  v
final API payload (MessageCreateParams)
```

这条图里最重要的不是"normalize"这个词有多高级，而是：

**所有来源先分清边界，再在最后一步统一整理。**

## system prompt 为什么不是全部

这是初学者非常容易混的一个点。

system prompt 适合放：

- 身份
- 规则
- 工具能力描述
- 长期说明

但有些东西不适合放进去：

- 这一轮刚发生的 tool_result
- 某个 hook 刚注入的补充说明
- 某条 memory attachment
- 当前临时提醒

这些更适合存在消息流里，而不是塞进 prompt block。

## 关键数据结构

### 1. SystemPromptBlock

在 Java 中用 record 表达：

```java
/**
 * 系统提示词块 —— system prompt 内部的一段结构化片段。
 */
public record SystemPromptBlock(
    String text,
    String cacheScope   // nullable，可选的缓存标识
) {
    public SystemPromptBlock(String text) {
        this(text, null);
    }
}
```

最小教学版可以只理解成：

- 一段文本
- 可选的缓存信息

### 2. PromptParts

```java
/**
 * 提示词部件 —— 系统提示词的 6 个独立段落。
 */
public record PromptParts(
    String core,         // 核心身份和基本指令
    String tools,        // 工具清单
    String skills,       // 技能元数据
    String memory,       // 持久记忆
    String agentMd,      // AGENT.md 指令链
    String dynamic       // 动态上下文（日期、平台等）
) {
    public String assemble() {
        return Stream.of(core, tools, skills, memory, agentMd, dynamic)
            .filter(Objects::nonNull)
            .filter(s -> !s.isBlank())
            .collect(Collectors.joining("\n\n"));
    }
}
```

### 3. NormalizedMessage

在 Anthropic Java SDK 中，消息最终都要变成 `MessageCreateParams` 的一部分：

```java
/**
 * 规范化消息 —— 统一的消息表示。
 */
public record NormalizedMessage(
    String role,                        // "user" | "assistant"
    List<ContentBlock> content          // 块列表
) {}

// ContentBlock 是一个 sealed 接口
sealed interface ContentBlock
    permits TextBlock, ToolUseBlock, ToolResultBlock, ImageBlock {
    String type();
}

record TextBlock(String text) implements ContentBlock {
    public String type() { return "text"; }
}

record ToolUseBlock(String id, String name, Map<String, Object> input)
    implements ContentBlock {
    public String type() { return "tool_use"; }
}

record ToolResultBlock(String toolUseId, String content, boolean isError)
    implements ContentBlock {
    public String type() { return "tool_result"; }
}
```

这里的 `content` 建议直接理解成"块列表"，而不是只是一段字符串。
因为后面你会自然遇到：

- text block
- tool_use block
- tool_result block
- attachment-like block

### 4. ReminderMessage

```java
/**
 * 提醒消息 —— 当前轮或当前阶段临时追加的系统信息。
 */
public record ReminderMessage(
    String content
) {
    // 教学版里不一定真的要用 system role 单独传
    // 但心智上要区分：这是长期 prompt block 还是当前轮临时 reminder
    public UserMessage toUserMessage() {
        // 实现中通常以 UserMessage 包裹，附加 system-reminder 标记
        return UserMessage.of("[system-reminder] " + content);
    }
}
```

## 最小实现

### 第一步：继续保留 `SystemPromptBuilder`

这一步不能丢。

```java
// s10 的 SystemPromptBuilder 继续存在
String systemPrompt = promptBuilder.build();
```

### 第二步：把消息输入做成独立管道

```java
/**
 * 构建消息管道 —— 把多种来源的消息规范化、拼接记忆、追加提醒。
 */
public class MessagePipeline {

    public List<Message> buildMessages(List<Message> rawMessages,
                                        List<Attachment> attachments,
                                        List<ReminderMessage> reminders) {
        List<Message> messages = normalizeMessages(rawMessages);
        messages = attachMemory(messages, attachments);
        messages = appendReminders(messages, reminders);
        return messages;
    }

    private List<Message> normalizeMessages(List<Message> raw) {
        // 把不同格式的消息统一为 SDK 兼容格式
        return raw.stream()
            .map(this::normalizeOne)
            .filter(Objects::nonNull)
            .toList();
    }

    private List<Message> attachMemory(List<Message> messages,
                                        List<Attachment> attachments) {
        if (attachments.isEmpty()) return messages;
        List<Message> result = new ArrayList<>(messages);
        for (Attachment att : attachments) {
            result.add(UserMessage.of(att.toContent()));
        }
        return result;
    }

    private List<Message> appendReminders(List<Message> messages,
                                           List<ReminderMessage> reminders) {
        if (reminders.isEmpty()) return messages;
        List<Message> result = new ArrayList<>(messages);
        for (ReminderMessage reminder : reminders) {
            result.add(reminder.toUserMessage());
        }
        return result;
    }
}
```

### 第三步：在最后一层统一生成 API payload

这一步特别关键。

在 Anthropic Java SDK 中，最终 payload 是 `MessageCreateParams`：

```java
MessageCreateParams params = MessageCreateParams.builder()
    .system(systemPrompt)                       // system prompt blocks
    .messages(messages)                         // normalized messages
    .tools(tools)                               // tool definitions
    .maxTokens(4096)
    .build();

Message response = client.messages().create(params);
```

它会让读者明白：

**system prompt、messages、tools 是并列输入面，而不是互相替代。**

## 一张更完整但仍然容易理解的图

```text
Prompt Blocks (SystemPromptBuilder)
  - core
  - tools
  - memory
  - AGENT.md
  - dynamic context

Messages (MessagePipeline)
  - UserMessage
  - AssistantMessage
  - ToolResultBlock messages
  - injected reminders

Attachments
  - memory attachment
  - hook attachment

          |
          v
   normalize + assemble
          |
          v
   MessageCreateParams
     .system(systemPrompt)
     .messages(messages)
     .tools(tools)
```

## 什么时候该放在 prompt，什么时候该放在 message

可以先记这个简单判断法：

### 更适合放在 prompt block

- 长期稳定规则
- 工具列表
- 长期身份说明
- AGENT.md 指令链（`.agent/AGENT.md`）

### 更适合放在 message 流

- 当前轮 tool_result
- 刚发生的提醒
- 当前轮追加的上下文
- 某次 hook 输出

### 更适合做 attachment

- 大块但可选的补充信息
- 需要按需展开的说明

## 初学者最容易犯的错

### 1. 把所有东西都塞进 system prompt

这样会让 prompt 越来越脏，也会模糊稳定信息和动态信息的边界。

### 2. 完全不做 normalize

随着消息来源增多，输入格式会越来越不稳定。

### 3. 把 memory、hook、tool_result 都当成一类东西

它们都能影响模型，但进入输入层的方式并不相同。

### 4. 忽略"临时 reminder"这一层

这会让很多本该只活一轮的信息，被错误地塞进长期 system prompt。

## 它和 `s10`、`s19` 的关系

- `s10`（`S10SystemPrompt.java`）讲 prompt builder
- 这篇讲 message + prompt 的完整输入管道
- `s19`（`S19McpPlugin.java`）则会把 MCP 带来的额外说明和外部能力继续接入这条管道

也就是说：

**builder 是 prompt 的内部结构，pipeline 是模型输入的整体结构。**

## Anthropic Java SDK 的消息类型映射

在 mini-agent-4j 中，所有消息最终都要映射到 Anthropic Java SDK 的类型：

```text
内部类型              SDK 类型                    用途
------------------------------------------------------------------------
UserMessage          UserMessage                用户输入
AssistantMessage     AssistantMessage           模型回复
ToolUseBlock         ToolUseBlock               工具调用意图
ToolResultBlock      ToolResultBlock            工具执行结果
TextBlock            TextBlock                  文本内容
ImageBlock           ImageBlock                 图片内容
system prompt        String (.system())         系统提示词（不在 messages 里）
```

理解这个映射很重要，因为：

- `system` 和 `messages` 在 API 中是并列的，不是嵌套的
- `ToolUseBlock` 出现在 `AssistantMessage` 的 content 里
- `ToolResultBlock` 出现在 `UserMessage` 的 content 里

## 教学边界

这篇最重要的，不是罗列所有输入来源，而是先把三条管线边界讲稳：

- 什么该进 system blocks
- 什么该进 normalized messages
- 什么只应该作为临时 reminder 或 attachment

只要这三层边界清楚，读者就已经能自己搭出一条可靠输入管道。
更细的 cache scope、attachment 去重和大结果外置，都可以放到后续扩展里再补。

## 一句话记住

**真正送给模型的，不只是一个 prompt，而是"prompt blocks + normalized messages + attachments + reminders"组成的输入管道。**
