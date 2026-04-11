# s08: Hook System

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > [ s08 ] s09 > s10 > s11 > s12`

> *"Don't modify the loop -- extend it."*
>
> **Harness layer**: The hook system -- extension points that inject behavior into the agent loop without rewriting it.

## Problem

Teams need to customize agent behavior: enforce code-style checks before writes, log all tool calls for auditing, or inject context after reads. But modifying the agent loop for each customization creates fragile, forked code. You need a plugin architecture where behavior is injected, not hardcoded.

## Solution

```
  Hook events:
  +-----------------+----------------------------------------------+
  | SessionStart    | Fires once when the REPL starts              |
  | PreToolUse      | Fires before tool execution (can BLOCK)      |
  | PostToolUse     | Fires after tool execution (can INJECT)      |
  +-----------------+----------------------------------------------+

  Exit code contract:
  +-----------------+----------------------------------------------+
  | 0               | Continue (stdout may contain JSON overrides) |
  | 1               | Block execution (stderr = reason)            |
  | 2               | Inject message (stderr = text to inject)     |
  +-----------------+----------------------------------------------+
```

Hooks are external scripts loaded from `.hooks.json`. They run in subprocesses, communicate via environment variables and exit codes, and never touch the agent loop code.

## How It Works

### Hook definition and loading

```json
{
  "hooks": {
    "PreToolUse": [
      {"matcher": "bash", "command": "python check.py"}
    ],
    "PostToolUse": [
      {"matcher": "*", "command": "python log.py"}
    ],
    "SessionStart": [
      {"matcher": "*", "command": "python init.py"}
    ]
  }
}
```

The `matcher` field filters by tool name: `"*"` matches all tools, `"bash"` only matches the bash tool.

### HookManager loads and executes hooks

```java
static class HookManager {
    HookResult runHooks(String event, Map<String, Object> context) {
        HookResult result = new HookResult();

        // Trust gate: hooks only run in trusted workspaces
        if (!checkWorkspaceTrust()) return result;

        for (HookDefinition hookDef : eventHooks) {
            // Matcher filter: "*" matches all, otherwise exact match
            if (!"*".equals(matcher) && !matcher.equals(toolName)) continue;

            // Set environment variables for the subprocess
            env.put("HOOK_EVENT", event);
            env.put("HOOK_TOOL_NAME", toolName);
            env.put("HOOK_TOOL_INPUT", inputJson);   // truncated to 10000 chars
            env.put("HOOK_TOOL_OUTPUT", outputStr);   // PostToolUse only

            // Execute subprocess (30s timeout), parse exit code
        }
        return result;
    }
}
```

### Exit code handling

```java
if (exitCode == 0) {
    // Continue -- stdout may contain JSON:
    //   updatedInput      -> modify tool parameters
    //   additionalContext  -> append context to result
    //   permissionDecision -> override permission check
} else if (exitCode == 1) {
    // BLOCK -- tool execution is skipped
    result.blocked = true;
    result.blockReason = stderrStr;
} else if (exitCode == 2) {
    // INJECT -- add message to tool result
    result.messages.add(stderrStr);
}
```

### Hook-aware agent loop

```java
for (ContentBlock block : response.content()) {
    if (!block.isToolUse()) continue;

    // ---- PreToolUse Hook ----
    HookResult preResult = hookManager.runHooks("PreToolUse", hookContext);
    if (preResult.blocked) {
        output = "Tool blocked by PreToolUse hook: " + reason;
        continue;  // skip tool execution
    }

    // ---- Execute tool ----
    output = handler.apply(effectiveInput);

    // ---- PostToolUse Hook ----
    hookContext.put("tool_output", output);
    HookResult postResult = hookManager.runHooks("PostToolUse", hookContext);
    output += postResult.messages;  // append injected messages
}
```

The loop itself is unchanged -- hooks wrap around tool execution as before/after advice.

### Workspace trust

Hooks only run in trusted workspaces, verified by a marker file:

```java
private boolean checkWorkspaceTrust() {
    if (sdkMode) return true;
    return Files.exists(TRUST_MARKER);  // .claude/.claude_trusted
}
```

## What Changed

| Component     | Before          | After                                     |
|---------------|-----------------|-------------------------------------------|
| Tool dispatch | Direct execute  | PreToolUse hook -> execute -> PostToolUse hook |
| Extension     | Modify loop code| External scripts in `.hooks.json`         |
| Blocking      | (none)          | Exit code 1 blocks execution              |
| Injection     | (none)          | Exit code 2 injects messages              |
| Context mods  | (none)          | Exit code 0 can update tool input         |
| Key classes   | (none)          | `HookManager`, `HookDefinition`, `HookResult` |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S08HookSystem"
```

1. Create a `.hooks.json` file in your working directory:

```json
{
  "hooks": {
    "PreToolUse": [
      {"matcher": "bash", "command": "echo \"Checking: $HOOK_TOOL_NAME\" && exit 0"}
    ],
    "PostToolUse": [
      {"matcher": "*", "command": "echo \"[audit] $(date): $HOOK_TOOL_NAME executed\" >&2 && exit 2"}
    ]
  }
}
```

2. Run the agent and try: `List files in this directory`
3. Observe the PreToolUse and PostToolUse hook output
4. Change a hook's exit code to `1` to block execution
