# s04: Subagent

`s01 > s02 > s03 > [ s04 ] s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"Process isolation gives you context isolation for free."* -- a fresh message list is a fresh mind.
>
> **Harness layer**: The `task` tool -- spawning a child agent with empty context.

## Problem

Long conversations accumulate noise. After 20 tool calls about refactoring, the model's context is polluted with stale file contents, abandoned approaches, and outdated error messages. You want to delegate a sub-task to a *clean slate* -- a fresh agent that sees only the task description, not the parent's entire history.

## Solution

```
+----------+      +-------+      +---------+
|  Parent  |      |       |      | Dispatch|
| messages | ---> |  LLM  | ---> |   Map   |
| [long...] |     |       |      +----+----+
+----------+      +---+---+           |
                      ^               v
                      |         +-----+------+
                      |         | task tool  |
                      |         +-----+------+
                      |               |
                      |               v
                      |    +----------+----------+
                      |    |   Subagent Loop      |
                      |    |   messages = [prompt] |   ← fresh context!
                      |    |   tools: no "task"   |   ← no recursion
                      |    +----------+----------+
                      |               |
                      +--- summary text (only result returned to parent)
```

The sub-agent gets a completely fresh `messages` list containing only the task prompt. It shares the filesystem but not the conversation history. When done, only a text summary returns to the parent.

## How It Works

1. **Define the `task` tool** on the parent agent. It takes a `prompt` and an optional `description`.

```java
AgentLoop.defineTool("task",
    "Spawn a subagent with fresh context. It shares the filesystem but not conversation history.",
    Map.of("prompt", Map.of("type", "string"),
           "description", Map.of("type", "string")),
    List.of("prompt"))
```

2. **Prepare the child's tool set.** The child gets `bash`, `read_file`, `write_file`, `edit_file` -- but NOT `task` to prevent recursive spawning.

```java
List<Tool> childTools = List.of(/* bash, read, write, edit -- no "task" */);
ToolDispatcher childDispatcher = new ToolDispatcher();
// register same file tools...
```

3. **`runSubagent()` creates a fresh context.** A brand-new `MessageCreateParams.Builder` with only the task prompt:

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
            // Extract text summary and return to parent
            return extractText(response);
        }
        // Execute tools, append results...
    }
    return "(subagent reached max rounds)";
}
```

4. **The parent receives only the summary.** The child's entire conversation history is discarded. The parent's context stays clean.

## What Changed

| Component       | s03                | s04                              |
|-----------------|--------------------|----------------------------------|
| Tools           | 5 (+todo)          | +1: `task` (parent only)         |
| Child tools     | (n/a)              | 4 tools (no `task` -- no recursion) |
| Context         | Single message list | Parent + independent child list  |
| Max rounds      | Unlimited          | Child capped at 30 rounds        |
| Return value    | (n/a)              | Text summary only                |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S04Subagent"
```

1. `Use the task tool to explore the project structure and give me a summary`
2. `Create a subtask to find all TODO comments in the codebase`
3. `Delegate a search task: find which files import java.nio.file.Path`
