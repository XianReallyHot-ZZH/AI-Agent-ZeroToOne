# s02：工具分发

`s00 > s01 > [ s02 ] > s03 > s04 > s05 > s06 > s07 > s08 > s09 > s10 > s11 > s12 > s13 > s14 > s15 > s16 > s17 > s18 > s19`

> *"循环根本没改。我只是加了工具。"* —— 一张分发表就够了。
>
> **装置层**：分发表 —— 将工具调用路由到处理函数。

## 这一章要解决什么问题

Bash 无所不能，但太粗糙。`cat` 能读文件，但模型得自己解析原始输出。`sed` 能编辑，但模型得构造转义安全的命令。每一次 bash 调用都是不受约束的安全面。

你需要专用工具，让模型的意图干净地映射到具体操作：读、写、编辑 —— 每个都有清晰的 schema。路径操作应该在工具层面做沙箱校验，而不是寄希望于模型自己构造安全的 shell 命令。

关键洞察：加工具不需要改循环。

## 解决方案

```
+--------+      +-------+      +------------------+
|  User  | ---> |  LLM  | ---> | Tool Dispatch    |
| prompt |      |       |      | {                |
+--------+      +---+---+      |   bash: run_bash |
                    ^           |   read: run_read |
                    |           |   write: run_wr  |
                    +-----------+   edit: run_edit |
                    tool_result | }                |
                                +------------------+

The dispatch map is a Map<String, Function<Map<String, Object>, String>>.
One lookup replaces any if/else chain.
```

添加一个工具 = 添加一个处理函数 + 一个 schema 条目。循环本身永远不变。

## 工作原理

1. **每个工具有一个处理函数。路径沙箱防止逃逸工作区。**

```java
private static Path safePath(String relativePath) {
    Path resolved = WORK_DIR.resolve(relativePath).normalize().toAbsolutePath();
    if (!resolved.startsWith(WORK_DIR)) {
        throw new SecurityException("Path escapes workspace: " + relativePath);
    }
    return resolved;
}

private static String runRead(String path, Integer limit) {
    Path safe = safePath(path);
    List<String> lines = Files.readAllLines(safe);
    if (limit != null && limit > 0 && limit < lines.size()) {
        int total = lines.size();
        lines = new ArrayList<>(lines.subList(0, limit));
        lines.add("... (" + (total - limit) + " more lines)");
    }
    String result = String.join("\n", lines);
    return result.length() > MAX_OUTPUT
            ? result.substring(0, MAX_OUTPUT)
            : result;
}
```

2. **分发表将工具名映射到处理函数。** 用 `Map<String, Function<Map<String, Object>, String>>` 一行查找替代 if/else 链。

```java
Map<String, Function<Map<String, Object>, String>> toolHandlers = new LinkedHashMap<>();
toolHandlers.put("bash",       input -> runBash((String) input.get("command")));
toolHandlers.put("read_file",  input -> runRead((String) input.get("path"),
                                                 (Integer) toInt(input.get("limit"))));
toolHandlers.put("write_file", input -> runWrite((String) input.get("path"),
                                                  (String) input.get("content")));
toolHandlers.put("edit_file",  input -> runEdit((String) input.get("path"),
                                                 (String) input.get("old_text"),
                                                 (String) input.get("new_text")));
```

3. **循环中按名称查找处理函数。** 循环体本身与 s01 完全一致。

```java
for (ContentBlock block : response.content()) {
    if (block.isToolUse()) {
        ToolUseBlock toolUse = block.asToolUse();
        String toolName = toolUse.name();
        Map<String, Object> input = (Map<String, Object>) jsonValueToObject(toolUse._input());

        // 从分发表查找并执行
        Function<Map<String, Object>, String> handler = toolHandlers.get(toolName);
        String output = (handler != null) ? handler.apply(input) : "Unknown tool: " + toolName;

        // 构造 tool_result 回传给 LLM
        toolResults.add(ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                        .toolUseId(toolUse.id())
                        .content(output)
                        .build()));
    }
}
```

加工具 = 加 handler + 加 schema。循环永远不变。

## 如果你开始觉得"工具不只是 handler map"

到这里为止，教学主线先把工具讲成：

- schema（`defineTool()` 声明 name、description、input schema）
- handler（`Function<Map<String, Object>, String>` 处理函数）
- `tool_result`（`ContentBlockParam.ofToolResult(...)` 结果回传）

这是对的，而且必须先这么学。

但如果你继续把系统做大，很快就会发现工具层还会继续长出：

- 权限环境
- 当前消息和 app state
- MCP client
- 文件读取缓存
- 通知与 query 跟踪

也就是说，在一个结构更完整的系统里，工具层最后会更像一条"工具控制平面"，而不只是一张分发表。

这层不要抢正文主线。
你先把这一章吃透，再继续看：

- [`s02a-tool-control-plane.md`](./s02a-tool-control-plane.md)

## 消息规范化

教学版的 `MessageCreateParams.Builder` 直接发给 API，所见即所发。但当系统变复杂后（工具超时、用户取消、压缩替换），内部消息列表会出现 API 不接受的格式问题。需要在发送前做一次规范化。

### 为什么需要

API 协议有三条硬性约束：

1. 每个 `tool_use` 块**必须**有匹配的 `tool_result`（通过 `tool_use_id` 关联）
2. `user` / `assistant` 消息必须**严格交替**（不能连续两条同角色）
3. 只接受协议定义的字段（内部元数据会导致 400 错误）

### Java 中的规范化约束

在 Python 参考实现中，`normalize_messages()` 函数在发送前遍历整个消息列表，处理三件事：剥离内部字段、补齐缺失的 tool_result、合并连续同角色消息。

在 Java SDK 中，`MessageCreateParams.Builder` 通过 `addMessage()`、`addUserMessage()`、`addUserMessageOfBlockParams()` 方法隐式维护消息顺序。但在以下场景中仍然需要手动规范化：

```java
// 场景 1：tool_result 配对
// 如果 agent 循环在工具执行前被中断（超时、用户取消），
// assistant 消息中的 tool_use block 就没有对应的 tool_result。
// 必须为每个未配对的 tool_use 补一个 "cancelled" 结果：

List<ContentBlockParam> toolResults = new ArrayList<>();
for (ContentBlock block : response.content()) {
    if (block.isToolUse()) {
        ToolUseBlock toolUse = block.asToolUse();
        // ... 执行工具 ...
        toolResults.add(ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                        .toolUseId(toolUse.id())
                        .content(output)       // 或 "(cancelled)"
                        .build()));
    }
}
// API 要求：tool_result 必须以 user 角色发送
paramsBuilder.addUserMessageOfBlockParams(toolResults);

// 场景 2：角色交替
// 如果连续两次调用 addUserMessage()，API 会拒绝。
// 需要在追加前检查最后一条消息的角色：

// 场景 3：字段剥离
// 内部使用的元数据（_timestamp、_source 等）不能出现在 API 请求中。
// Java SDK 的强类型消息对象天然过滤了这些字段，
// 但如果使用 Map 构造自定义消息，需要手动剥离非协议字段。
```

**关键洞察**：`MessageCreateParams.Builder` 中的消息列表是系统的内部表示，API 看到的是规范化后的结果。两者不是同一个东西。Java SDK 的强类型系统在编译期挡住了大部分格式错误，但 `tool_use` / `tool_result` 的配对关系和角色交替仍需在运行时保证。

## 教学边界

这一章最重要的，不是把完整工具运行时一次讲全，而是先讲清 3 个稳定点：

- tool schema 是给模型看的说明（`defineTool()` 声明 name、description、input schema）
- handler map 是代码里的分发入口（`Map<String, Function<...>>` 按名查找）
- `tool_result` 是结果回流到主循环的统一出口（`ContentBlockParam.ofToolResult(...)`）

只要这三点稳住，读者就已经能自己在不改主循环的前提下新增工具。

权限、hook、并发、流式执行、外部工具来源这些后续层次当然重要，但都应该建立在这层最小分发模型之后。

## 变更对比

| 组件          | s01           | s02                               |
|---------------|---------------|-----------------------------------|
| 工具          | `bash`（1 个） | `bash` + `read_file` + `write_file` + `edit_file`（4 个） |
| 分发表        | 硬编码 bash   | `Map<String, Function<...>>` 分发表 |
| 路径安全      | （无）        | `safePath()` 沙箱                 |
| Agent 循环    | 不变          | 不变                               |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S02ToolUse"
```

试试这些 prompt（英文 prompt 对 LLM 效果更好，也可以用中文）：

1. `读取 pom.xml 文件的内容`
2. `创建一个 notes.txt 文件，内容为 "s02 works"`
3. `编辑 notes.txt，把 "s02" 改成 "session 02"`
4. `列出 src/main/java 下的所有 Java 文件`
