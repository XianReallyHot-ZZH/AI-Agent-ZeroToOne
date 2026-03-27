# s01: The Agent Loop

`[ s01 ] s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"One loop & Bash is all you need"* -- one tool + one loop = an agent.
>
> **Harness layer**: The loop -- the model's first connection to the real world.

## Problem

A language model can reason about code, but it can't *touch* the real world -- can't read files, run tests, or check errors. Without a loop, every tool call requires you to manually copy-paste results back. You become the loop.

## Solution

```
+--------+      +-------+      +---------+
|  User  | ---> |  LLM  | ---> |  Tool   |
| prompt |      |       |      | execute |
+--------+      +---+---+      +----+----+
                    ^                |
                    |   tool_result  |
                    +----------------+
                    (loop until stopReason != TOOL_USE)
```

One exit condition controls the entire flow. The loop runs until the model stops calling tools.

## How It Works

1. **User prompt becomes the first message.** A `MessageCreateParams.Builder` accumulates the conversation history.

```java
paramsBuilder.addUserMessage(query);
```

2. **Send messages + tool definitions to the LLM.** The `AgentLoop` wraps the Anthropic Java SDK client.

```java
Message response = client.messages().create(paramsBuilder.build());
```

3. **Append the assistant response.** Check `stopReason` -- if the model didn't call a tool, we're done.

```java
paramsBuilder.addMessage(response);
if (!StopReason.TOOL_USE.equals(response.stopReason())) {
    // print text response and return
    return;
}
```

4. **Execute each tool call, collect results, append as a user message.** Loop back to step 2.

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

Assembled into one method in `AgentLoop.java`:

```java
public void agentLoop(MessageCreateParams.Builder paramsBuilder) {
    while (true) {
        Message response = client.messages().create(paramsBuilder.build());
        paramsBuilder.addMessage(response);
        if (!StopReason.TOOL_USE.equals(response.stopReason())) { return; }
        // ... dispatch tools, append results ...
    }
}
```

That's the entire agent. Everything else in this course layers on top -- **without changing the loop**.

## What Changed

| Component     | Before     | After                              |
|---------------|------------|------------------------------------|
| Agent loop    | (none)     | `while(true)` + `stopReason` check |
| Tools         | (none)     | `bash` (one tool)                  |
| Messages      | (none)     | `MessageCreateParams.Builder`      |
| Control flow  | (none)     | `stopReason != TOOL_USE`           |
| Key classes   | (none)     | `AgentLoop`, `ToolDispatcher`, `BashTool` |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S01AgentLoop"
```

1. `Create a file called hello.py that prints "Hello, World!"`
2. `List all files in this directory`
3. `What is the current git branch?`
4. `Create a directory called test_output and write 3 files in it`
