# s02：工具分发

`s01 > [ s02 ] s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"循环根本没改。我只是加了工具。"* —— 一张分发表就够了。
>
> **装置层**：分发表 —— 将工具调用路由到处理函数。

## 问题

Bash 无所不能，但太粗糙。`cat` 能读文件，但模型得自己解析原始输出。`sed` 能编辑，但模型得构造转义安全的命令。你需要专用工具，让模型的意图干净地映射到具体操作：读、写、编辑 —— 每个都有清晰的 schema。

## 方案

```
+--------+      +-------+      +---------+
|  用户   | ---> |  LLM  | ---> | 分发表  |
| 提示词  |      |       |      |         |
+--------+      +---+---+      +----+----+
                    ^               |
                    |               v
                    |        +------+------+------+------+
                    |        | bash  | read |write | edit |
                    |        +------+------+------+------+
                    |               |               |
                    +--- tool_result（来自匹配的处理函数）--+
```

添加一个工具 = 添加一个处理函数 + 一个 schema 条目。循环本身永远不变。

## 原理

1. **使用 `AgentLoop.defineTool()` 辅助方法定义工具 schema。** 每个工具声明名称、描述、输入属性和必填字段。

```java
List<Tool> tools = List.of(
    AgentLoop.defineTool("bash", "Run a shell command.",
        Map.of("command", Map.of("type", "string")),
        List.of("command")),
    AgentLoop.defineTool("read_file", "Read file contents.",
        Map.of("path", Map.of("type", "string"),
               "limit", Map.of("type", "integer")),
        List.of("path")),
    AgentLoop.defineTool("write_file", "Write content to file.",
        Map.of("path", Map.of("type", "string"),
               "content", Map.of("type", "string")),
        List.of("path", "content")),
    AgentLoop.defineTool("edit_file", "Replace exact text in file.",
        Map.of("path", Map.of("type", "string"),
               "old_text", Map.of("type", "string"),
               "new_text", Map.of("type", "string")),
        List.of("path", "old_text", "new_text"))
);
```

2. **在 `ToolDispatcher` 中注册处理函数。** 每个处理函数是一个 `ToolHandler` lambda（`Map<String, Object> -> String`）。

```java
ToolDispatcher dispatcher = new ToolDispatcher();
dispatcher.register("bash",       input -> BashTool.execute(input, workDir));
dispatcher.register("read_file",  input -> ReadTool.execute(input, sandbox));
dispatcher.register("write_file", input -> WriteTool.execute(input, sandbox));
dispatcher.register("edit_file",  input -> EditTool.execute(input, sandbox));
```

3. **PathSandbox 守卫所有文件工具。** 每个路径都会被解析、规范化，并验证是否在 `workDir` 范围内。

```java
PathSandbox sandbox = new PathSandbox(workDir);
// ReadTool 内部：Path safe = sandbox.safePath(input.get("path"));
```

4. **AgentLoop 不变。** `dispatcher.dispatch(toolName, input)` 按名称查找处理函数。如果工具抛出异常，dispatcher 会捕获并返回 `"Error: ..."`，确保循环永不崩溃。

## 变更对比

| 组件          | s01           | s02                               |
|---------------|---------------|-----------------------------------|
| 工具          | `bash`（1 个） | `bash` + `read_file` + `write_file` + `edit_file`（4 个） |
| 分发表        | 1 个处理函数  | 4 个处理函数                       |
| 路径安全      | （无）        | `PathSandbox`                     |
| Agent 循环    | 不变          | 不变                               |

## 试一试

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S02ToolUse"
```

1. `读取 pom.xml 文件的内容`
2. `创建一个 notes.txt 文件，内容为 "s02 works"`
3. `编辑 notes.txt，把 "s02" 改成 "session 02"`
4. `列出 src/main/java 下的所有 Java 文件`
