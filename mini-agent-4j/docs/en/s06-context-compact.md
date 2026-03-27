# s06: Context Compact

`s01 > s02 > s03 > s04 > s05 > [ s06 ] | s07 > s08 > s09 > s10 > s11 > s12`

> *"Agents can strategically forget, then work indefinitely."* -- nothing is truly lost, just moved out of active context.
>
> **Harness layer**: The compression pipeline -- three layers of memory management.

## Problem

Every tool call adds tokens. After 30 rounds, the conversation might be 100,000 tokens. The LLM has a context window limit, and past that point the API returns an error. You need to compress the conversation without losing critical information.

## Solution

```
Layer 1: microCompact (every turn, silent)
+---+---+---+---+---+---+      +---+---+---+---+---+---+
| r1| r2| r3| r4| r5| r6|  --> | [ ]| [ ]| [ ]| r4| r5| r6|  keep last 3
+---+---+---+---+---+---+      +---+---+---+---+---+---+

Layer 2: autoCompact (when tokens > threshold)
+---+---+---+---+---+---+      +---------+---------+
| r1| r2| r3| r4| r5| r6|  --> | summary | new msg |
| ...long history...      |     | (LLM)  |         |
+---+---+---+---+---+---+      +---------+---------+
         |                           ^
         v                           |
   .transcripts/              LLM summarizes
   saved to disk              full conversation

Layer 3: manualCompact (model calls `compact` tool)
  Same as Layer 2, but triggered by the model on demand.
```

Three layers work together: micro compression every turn, auto compression at a threshold, and manual compression when the model decides to forget.

## How It Works

1. **Layer 1: microCompact** -- silently replaces old `tool_result` content with placeholders every turn. Only the last 3 tool results are kept in full.

```java
// ContextCompressor.microCompact()
// Old: full output of "ls -la" (500 tokens)
// New: "[Previous: used bash]"
```

2. **Layer 2: autoCompact** -- when token count exceeds 50,000, save the full transcript to disk, then ask the LLM to summarize:

```java
ContextCompressor compressor = new ContextCompressor(client, model, transcriptDir);

// 1. Save full conversation to .transcripts/session_TTT.jsonl
// 2. Call LLM: "Summarize this conversation, preserving key decisions and file states"
// 3. Replace all messages with: [summary_message, new_user_message]
```

3. **Layer 3: manualCompact** -- the model can call the `compact` tool to trigger compression on demand:

```java
AgentLoop.defineTool("compact", "Trigger manual conversation compression.",
    Map.of("focus", Map.of("type", "string",
        "description", "What to preserve in the summary")),
    null)
```

4. **Transcripts are never deleted.** They're saved to `.transcripts/` as JSONL files. Nothing is truly lost -- just moved out of the active context window.

5. **Token estimation** uses a simple heuristic: `chars / 4`. This is approximate but sufficient for threshold detection:

```java
// TokenEstimator
long estimate(String text) { return text.length() / 4; }
```

## What Changed

| Component       | s05                | s06                              |
|-----------------|--------------------|----------------------------------|
| Tools           | 6                  | +1: `compact`                    |
| Compression     | (none)             | 3-layer pipeline                 |
| Token tracking  | (none)             | `TokenEstimator` (chars/4)       |
| Disk storage    | (none)             | `.transcripts/` JSONL files      |
| New class       | `SkillLoader`      | `ContextCompressor`, `TokenEstimator` |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S06ContextCompact"
```

1. `Read every Java file in src/main/java and summarize the project` (generates lots of context)
2. `Now use the compact tool to compress the conversation`
3. `What do you remember about the project after compression?`
