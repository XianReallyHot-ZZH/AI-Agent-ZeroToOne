# s02: Tool Use

`s01 > [ s02 ] s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"The loop didn't change at all. I just added tools."* -- a dispatch map is all you need.
>
> **Harness layer**: The dispatch map -- routing tool calls to handler functions.

## Problem

Bash can do anything, but it's blunt. `cat` works for reading, but the model has to parse raw output. `sed` works for editing, but the model has to craft escape-safe commands. You want purpose-built tools that map cleanly to the model's intent: read, write, edit -- each with a clear schema.

## Solution

```
+--------+      +-------+      +---------+
|  User  | ---> |  LLM  | ---> | Dispatch|
| prompt |      |       |      |  Map    |
+--------+      +---+---+      +----+----+
                    ^               |
                    |               v
                    |        +------+------+------+------+
                    |        | bash  | read  |write | edit |
                    |        +------+------+------+------+
                    |               |               |
                    +--- tool_result (from matched handler) +
```

Adding a tool means adding one handler function + one schema entry. The loop itself never changes.

## How It Works

1. **Define tool schemas** using the `AgentLoop.defineTool()` helper. Each tool declares its name, description, input properties, and required fields.

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

2. **Register handlers** in the `ToolDispatcher`. Each handler is a `ToolHandler` lambda (`Map<String, Object> -> String`).

```java
ToolDispatcher dispatcher = new ToolDispatcher();
dispatcher.register("bash",      input -> BashTool.execute(input, workDir));
dispatcher.register("read_file", input -> ReadTool.execute(input, sandbox));
dispatcher.register("write_file",input -> WriteTool.execute(input, sandbox));
dispatcher.register("edit_file", input -> EditTool.execute(input, sandbox));
```

3. **PathSandbox** guards all file tools. Every path is resolved, normalized, and verified to stay within `workDir`.

```java
PathSandbox sandbox = new PathSandbox(workDir);
// inside ReadTool: Path safe = sandbox.safePath(input.get("path"));
```

4. **The AgentLoop is unchanged.** `dispatcher.dispatch(toolName, input)` looks up the handler by name. If the tool throws, the dispatcher catches it and returns `"Error: ..."` so the loop never crashes.

## What Changed

| Component       | s01           | s02                              |
|-----------------|---------------|----------------------------------|
| Tools           | `bash` (1)    | `bash` + `read_file` + `write_file` + `edit_file` (4) |
| Dispatch map    | 1 handler     | 4 handlers                       |
| Path safety     | (none)        | `PathSandbox`                    |
| Agent loop      | unchanged     | unchanged                        |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S02ToolUse"
```

1. `Read the pom.xml file`
2. `Create a file called notes.txt with the content "s02 works"`
3. `Edit notes.txt and change "s02" to "session 02"`
4. `List all Java files in src/main/java`
