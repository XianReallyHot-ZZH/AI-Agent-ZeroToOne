# s01: The Agent Loop (智能体循环)

`s00 > [ s01 ] > s02 > s03 > s04 > s05 > s06 > s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *没有循环，就没有 agent。*
> 这一章先教你做出一个最小但正确的循环，再告诉你为什么后面还需要更完整的控制平面。

## 这一章要解决什么问题

语言模型本身只会"生成下一段内容"。

它不会自己：

- 打开文件
- 运行命令
- 观察报错
- 把工具结果再接着用于下一步推理

如果没有一层代码在中间反复做这件事：

```text
发请求给模型
  -> 发现模型想调工具
  -> 真的去执行工具
  -> 把结果再喂回模型
  -> 继续下一轮
```

那模型就只是一个"会说话的程序"，还不是一个"会干活的 agent"。

所以这一章的核心目标只有一个：

**把"模型 + 工具"连接成一个能持续推进任务的主循环。**

## 先解释几个名词

### 什么是 loop

`loop` 就是循环。

这里的意思不是"程序死循环"，而是：

> 只要任务还没做完，系统就继续重复同一套步骤。

### 什么是 turn

`turn` 可以理解成"一轮"。

最小版本里，一轮通常包含：

1. 把当前消息发给模型
2. 读取模型回复
3. 如果模型调用了工具，就执行工具
4. 把工具结果写回消息历史

然后才进入下一轮。

### 什么是 tool_result

`tool_result` 就是工具执行结果。

它不是随便打印在终端上的日志，而是：

> 要重新写回对话历史、让模型下一轮真的能看见的结果块。

在 Java SDK 中，它表现为 `ToolResultBlockParam`，需要关联 `toolUseId` 才能告诉模型"这条结果对应的是你刚才哪一次工具调用"。

### 什么是 state

`state` 是"当前运行状态"。

第一次看到这个词时，你可以先把它理解成：

> 主循环继续往下走时，需要一直带着走的那份数据。

最小版本里，最重要的状态就是：

- `List<Message>` — 对话历史（通过 `MessageCreateParams.Builder` 累积）
- 当前是第几轮
- 这一轮结束后为什么还要继续

## 最小心智模型

先把整个 agent 想成下面这条回路：

```text
user message
   |
   v
LLM
   |
   +-- 普通回答 (stopReason=END_TURN) --> 结束
   |
   +-- tool_use (stopReason=TOOL_USE) --> 执行工具
                                           |
                                           v
                                      tool_result
                                           |
                                           v
                                      写回 paramsBuilder
                                           |
                                           v
                                      下一轮继续
```

这条图里最关键的，不是"有一个 `while (true)`"。

真正关键的是这句：

**工具结果必须重新进入消息历史，成为下一轮推理的输入。**

如果少了这一步，模型就无法基于真实观察继续工作。

## 关键数据结构

### 1. Message

最小教学版里，消息由 `MessageCreateParams.Builder` 管理。

每条消息有一个 `role`：

- `user` — 用户的输入，或者工具执行结果的包装
- `assistant` — 模型的回复

这里最重要的不是字段名字，而是你要记住：

**消息历史不是聊天记录展示层，而是模型下一轮要读的工作上下文。**

### 2. ToolResultBlock

当工具执行完后，你要把它包装回消息流：

```java
ContentBlockParam.ofToolResult(
    ToolResultBlockParam.builder()
        .toolUseId(toolUse.id())   // 关联到对应的 tool_use 调用
        .content(output)           // 工具执行结果
        .build())
```

`toolUseId` 的作用很简单：

> 告诉模型"这条结果对应的是你刚才哪一次工具调用"。

### 3. LoopState

这章建议你不要只用一堆零散局部变量。

`MessageCreateParams.Builder` 本身就是最核心的状态容器——它累积了整个对话历史。

此外，最小也应该显式关注：

- **turn_count** — 当前第几轮（最小版可以不显式记录）
- **transition_reason** — 这一轮结束后为什么要继续

最小教学版只用一种原因就够了：

```java
StopReason.TOOL_USE
```

也就是：

> 因为刚执行完工具，所以要继续。

后面到了控制面更完整的章节里，你会看到它逐渐长成更多种原因。
如果你想先看完整一点的形状，可以配合读：

- [`s00a-query-control-plane.md`](./s00a-query-control-plane.md)

## 最小实现

### 第一步：准备初始消息

用户的请求先通过 `paramsBuilder` 进入消息历史：

```java
paramsBuilder.addUserMessage(query);
```

### 第二步：调用模型

把消息历史、system prompt 和工具定义一起发给模型：

```java
Message response = client.messages().create(paramsBuilder.build());
```

### 第三步：追加 assistant 回复

```java
paramsBuilder.addMessage(response);
```

这一步非常重要。

很多初学者会只关心"最后有没有答案"，忽略把 assistant 回复本身写回历史。
`addMessage(response)` 会自动展开 `response.content()` 中的所有 block。
少了这一步，下一轮上下文就会断掉。

### 第四步：如果模型调用了工具，就执行

```java
List<ContentBlockParam> toolResults = new ArrayList<>();
for (ContentBlock block : response.content()) {
    if (block.isToolUse()) {
        ToolUseBlock toolUse = block.asToolUse();
        Map<String, Object> input = (Map<String, Object>)
            jsonValueToObject(toolUse._input());
        String command = input != null
            ? (String) input.get("command")
            : "";
        String output = runBash(command);
        toolResults.add(ContentBlockParam.ofToolResult(
            ToolResultBlockParam.builder()
                .toolUseId(toolUse.id())
                .content(output)
                .build()));
    }
}
```

### 第五步：把工具结果作为新消息写回去

```java
paramsBuilder.addUserMessageOfBlockParams(toolResults);
```

然后下一轮重新发给模型。

### 组合成一个完整循环

```java
private static void agentLoop(AnthropicClient client,
                              MessageCreateParams.Builder paramsBuilder) {
    while (true) {
        // 1. 调用 LLM
        Message response = client.messages().create(paramsBuilder.build());

        // 2. 将 assistant 回复追加到历史
        paramsBuilder.addMessage(response);

        // 3. 检查停止原因
        boolean isToolUse = response.stopReason()
                .map(StopReason.TOOL_USE::equals)
                .orElse(false);

        if (!isToolUse) {
            // 模型决定停止，打印文本后返回
            for (ContentBlock block : response.content()) {
                block.text().ifPresent(textBlock ->
                        System.out.println(textBlock.text()));
            }
            return;
        }

        // 4. 执行每个工具调用，收集结果
        List<ContentBlockParam> toolResults = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            if (block.isToolUse()) {
                ToolUseBlock toolUse = block.asToolUse();
                Map<String, Object> input = (Map<String, Object>)
                        jsonValueToObject(toolUse._input());
                String output = runBash(
                        input != null ? (String) input.get("command") : "");
                toolResults.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                                .toolUseId(toolUse.id())
                                .content(output)
                                .build()));
            }
        }

        // 5. 将工具结果追加为 user 消息
        if (!toolResults.isEmpty()) {
            paramsBuilder.addUserMessageOfBlockParams(toolResults);
        }
    }
}
```

这就是最小 agent loop。

## 它如何接进整个系统

从现在开始，后面所有章节本质上都在做同一件事：

**往这个循环里增加新的状态、新的分支判断和新的执行能力。**

例如：

- `s02` 往里面接工具路由
- `s03` 往里面接规划状态
- `s06` 往里面接上下文压缩
- `s07` 往里面接权限判断
- `s11` 往里面接错误恢复

所以请把这一章牢牢记成一句话：

> agent 的核心不是"模型很聪明"，而是"系统持续把现实结果喂回模型"。

## 为什么教学版先接受 `StopReason.TOOL_USE` 这个简化

这一章里，我们先用：

```java
if (!StopReason.TOOL_USE.equals(response.stopReason())) {
    return;
}
```

这完全合理。

因为初学者在第一章真正要学会的，不是所有复杂边界，而是：

1. assistant 回复要写回历史
2. tool_result 要写回历史
3. 主循环要持续推进

但你也要知道，这只是第一层简化。

更完整的系统不会只依赖 `stopReason`，还会自己维护更明确的续行状态。
这是后面要补的，不是这一章一开始就要背下来的东西。

## 初学者最容易犯的错

### 1. 把工具结果打印出来，但不写回 `paramsBuilder`

这样模型下一轮根本看不到真实执行结果。

### 2. 只保存用户消息，不保存 assistant 消息

忘了调用 `paramsBuilder.addMessage(response)`，上下文会断层，模型会越来越不像"接着刚才做"。

### 3. 不给工具结果绑定 `toolUseId`

模型会分不清哪条结果对应哪次调用。必须用 `ToolResultBlockParam.builder().toolUseId(toolUse.id())` 正确关联。

### 4. 一上来就把流式、并发、恢复、压缩全塞进第一章

这会让主线变得非常难学。

第一章最重要的是先把最小回路搭起来。

### 5. 以为 `paramsBuilder` 只是聊天展示

不是。

在 agent 里，`MessageCreateParams.Builder` 累积的消息历史更像"下一轮工作输入"。

## 教学边界

这一章只需要先讲透一件事：

**Agent 之所以从"会说"变成"会做"，是因为模型输出能走到工具，工具结果又能回到下一轮模型输入。**

所以教学仓库在这里要刻意停住：

- 不要一开始就拉进 streaming、retry、budget、recovery
- 不要一开始就混入权限、Hook、任务系统
- 不要把第一章写成整套系统所有后续机制的总图

如果读者已经能凭记忆写出 `messages -> model -> tool_result -> next turn` 这条回路，这一章就已经达标了。

## 变更对比

| 组件       | 之前   | 之后                                         |
|------------|--------|----------------------------------------------|
| Agent 循环 | （无） | `while(true)` + `stopReason` 检查            |
| 工具       | （无） | `bash`（一个工具）                           |
| 消息       | （无） | `MessageCreateParams.Builder`                |
| 控制流     | （无） | `stopReason != TOOL_USE`                     |
| 关键类     | （无） | `AnthropicClient`、`MessageCreateParams`、`ToolResultBlockParam` |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S01AgentLoop"
```

1. `创建一个 hello.py 文件，输出 "Hello, World!"`
2. `列出当前目录下的所有文件`
3. `当前 git 分支是什么？`
4. `创建一个 test_output 目录并在里面写 3 个文件`

## 一句话记住

**Agent Loop 的本质，是把"模型的动作意图"变成"真实执行结果"，再把结果送回模型继续推理。**
