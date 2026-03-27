# s01：Agent 循环

`[ s01 ] s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"一个循环加一个 Bash 就够了"* —— 一个工具 + 一个循环 = 一个 Agent。
>
> **装置层（Harness layer）**：循环 —— 模型与真实世界的第一次连接。

## 问题

语言模型能推理代码，但它无法*触碰*真实世界 —— 不能读文件、跑测试、检查错误。没有循环，每次工具调用都需要你手动把结果复制粘贴回去。你变成了循环本身。

## 方案

```
+--------+      +-------+      +---------+
|  用户   | ---> |  LLM  | ---> |  工具   |
| 提示词  |      |       |      |  执行   |
+--------+      +---+---+      +----+----+
                    ^                |
                    |   tool_result  |
                    +----------------+
                    (循环直到 stopReason != TOOL_USE)
```

一个退出条件控制整个流程。循环一直运行，直到模型不再调用工具。

## 原理

1. **用户提示词成为第一条消息。** `MessageCreateParams.Builder` 负责累积对话历史。

```java
paramsBuilder.addUserMessage(query);
```

2. **将消息和工具定义发送给 LLM。** `AgentLoop` 封装了 Anthropic Java SDK 客户端。

```java
Message response = client.messages().create(paramsBuilder.build());
```

3. **追加助手回复。** 检查 `stopReason` —— 如果模型没有调用工具，流程结束。

```java
paramsBuilder.addMessage(response);
if (!StopReason.TOOL_USE.equals(response.stopReason())) {
    // 打印文本回复并返回
    return;
}
```

4. **执行每个工具调用，收集结果，追加为用户消息。** 回到第 2 步继续循环。

```java
List<ContentBlockParam> toolResults = new ArrayList<>();
for (ContentBlock block : response.content()) {
    if (block.isToolUse()) {
        ToolUseBlock toolUse = block.asToolUse();
        Map<String, Object> input = (Map<String, Object>)
            AgentLoop.jsonValueToObject(toolUse._input());
        String output = dispatcher.dispatch(toolUse.name(), input);
        toolResults.add(ContentBlockParam.ofToolResult(
            ToolResultBlockParam.builder()
                .toolUseId(toolUse.id())
                .content(output)
                .build()));
    }
}
paramsBuilder.addUserMessageOfBlockParams(toolResults);
```

组装成 `AgentLoop.java` 中的一个方法：

```java
public void agentLoop(MessageCreateParams.Builder paramsBuilder) {
    while (true) {
        Message response = client.messages().create(paramsBuilder.build());
        paramsBuilder.addMessage(response);
        if (!StopReason.TOOL_USE.equals(response.stopReason())) { return; }
        // ... 分发工具，追加结果 ...
    }
}
```

这就是整个 Agent。本课程后续所有内容都在此之上叠加 —— **循环本身从不改变**。

## 变更对比

| 组件          | 之前       | 之后                              |
|---------------|------------|-----------------------------------|
| Agent 循环    | （无）     | `while(true)` + `stopReason` 检查 |
| 工具          | （无）     | `bash`（一个工具）                |
| 消息          | （无）     | `MessageCreateParams.Builder`     |
| 控制流        | （无）     | `stopReason != TOOL_USE`          |
| 关键类        | （无）     | `AgentLoop`、`ToolDispatcher`、`BashTool` |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S01AgentLoop"
```

1. `创建一个 hello.py 文件，输出 "Hello, World!"`
2. `列出当前目录下的所有文件`
3. `当前 git 分支是什么？`
4. `创建一个 test_output 目录并在里面写 3 个文件`
