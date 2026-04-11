# s04：Subagent

`s01 > s02 > s03 > [ s04 ] s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *"进程隔离免费提供上下文隔离。"* —— 一份全新的消息列表就是一颗全新的头脑。
>
> **装置层**：`task` 工具 —— 生成拥有空上下文的子 Agent。

## 问题

长对话会积累噪音。经过 20 次关于重构的工具调用后，模型的上下文被过时的文件内容、放弃的方案和过时的错误信息所污染。你想把子任务委托给一张*白纸* —— 一个只看到任务描述、看不到父级全部历史的新 Agent。

## 方案

```
+----------+      +-------+      +---------+
|  父 Agent |      |       |      | 分发表  |
| messages | ---> |  LLM  | ---> |         |
| [很长...]  |     |       |      +----+----+
+----------+      +---+---+           |
                      ^               v
                      |         +-----+------+
                      |         | task 工具  |
                      |         +-----+------+
                      |               |
                      |               v
                      |    +----------+----------+
                      |    |   子 Agent 循环      |
                      |    |   messages = [prompt] |   ← 全新上下文！
                      |    |   tools: 无 "task"   |   ← 防递归
                      |    +----------+----------+
                      |               |
                      +--- 摘要文本（只有结果返回给父 Agent）
```

子 Agent 拥有完全全新的 `messages` 列表，只包含任务提示词。它共享文件系统，但不共享对话历史。完成后只有一段文本摘要返回给父 Agent。

## 原理

1. **在父 Agent 上定义 `task` 工具。** 接收 `prompt` 和可选的 `description`。

```java
AgentLoop.defineTool("task",
    "Spawn a subagent with fresh context. It shares the filesystem but not conversation history.",
    Map.of("prompt", Map.of("type", "string"),
           "description", Map.of("type", "string")),
    List.of("prompt"))
```

2. **准备子 Agent 的工具集。** 子 Agent 获得 `bash`、`read_file`、`write_file`、`edit_file` —— 但没有 `task`，以防止递归生成。

```java
List<Tool> childTools = List.of(/* bash、read、write、edit —— 无 "task" */);
ToolDispatcher childDispatcher = new ToolDispatcher();
// 注册相同的文件工具...
```

3. **`runSubagent()` 创建全新上下文。** 一个全新的 `MessageCreateParams.Builder`，只包含任务提示词：

```java
private static String runSubagent(AgentLoop agent, String prompt,
                                   ToolDispatcher childDispatcher, List<Tool> childTools) {
    var subBuilder = MessageCreateParams.builder()
        .model(agent.getModel())
        .maxTokens(8000)
        .system("You are a coding subagent. Complete the given task, then summarize.");

    for (Tool tool : childTools) subBuilder.addTool(tool);
    subBuilder.addUserMessage(prompt);

    for (int round = 0; round < 30; round++) {
        Message response = agent.getClient().messages().create(subBuilder.build());
        subBuilder.addMessage(response);
        if (!StopReason.TOOL_USE.equals(response.stopReason())) {
            // 提取文本摘要并返回给父 Agent
            return extractText(response);
        }
        // 执行工具，追加结果...
    }
    return "(subagent reached max rounds)";
}
```

4. **父 Agent 只收到摘要。** 子 Agent 的完整对话历史被丢弃。父 Agent 的上下文保持干净。

## 变更对比

| 组件          | s03                 | s04                               |
|---------------|---------------------|-----------------------------------|
| 工具          | 5 个（+todo）       | +1：`task`（仅父 Agent）          |
| 子 Agent 工具 | （不适用）          | 4 个工具（无 `task` —— 无递归）    |
| 上下文        | 单一消息列表        | 父级 + 独立子级列表               |
| 最大轮数      | 无限制              | 子 Agent 上限 30 轮               |
| 返回值        | （不适用）          | 仅文本摘要                        |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S04Subagent"
```

1. `用 task 工具探索项目结构，给我一个摘要`
2. `创建一个子任务：找出代码库中所有的 TODO 注释`
3. `委托一个搜索任务：找出哪些文件导入了 java.nio.file.Path`
