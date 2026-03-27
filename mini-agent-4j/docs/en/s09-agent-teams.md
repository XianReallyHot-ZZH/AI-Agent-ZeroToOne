# s09: Agent Teams

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > [ s09 ] s10 > s11 > s12`

> *"Teammates are named, have memory, and can be talked to at any time."* -- from ephemeral subagents to persistent collaborators.
>
> **Harness layer**: The MessageBus + TeamManager -- persistent agents that communicate via file-based inboxes.

## Problem

s04's subagents are fire-and-forget: spawn, execute, return summary, destroyed. You can't send a follow-up message. You can't ask "how's it going?" You need persistent agents with names, memory, and the ability to communicate asynchronously.

## Solution

```
Lead Agent (REPL)
  |
  +--- spawn_teammate("alice", "backend", "You are a Java backend engineer...")
  |         |
  |         v
  |    [alice's Virtual Thread + own agent loop + own messages]
  |
  +--- spawn_teammate("bob", "frontend", "You are a React developer...")
  |         |
  |         v
  |    [bob's Virtual Thread + own agent loop + own messages]
  |
  +--- MessageBus (.team/inbox/)
        |
        +-- alice.jsonl  (append-only inbox)
        +-- bob.jsonl    (append-only inbox)
        +-- lead.jsonl   (lead's inbox for responses)
```

Each teammate runs in its own Virtual Thread with an independent agent loop. Communication happens through JSONL file-based inboxes.

## How It Works

1. **MessageBus** implements file-based inter-agent communication. Each agent gets an inbox file:

```java
MessageBus bus = new MessageBus(teamDir.resolve("inbox"));

// Send a message (append one JSON line)
bus.send("lead", "alice", "Please implement the UserService class", "message", null);

// Read and drain inbox (read all, then clear)
List<Map<String, Object>> messages = bus.readInbox("alice");

// Broadcast to all teammates
bus.broadcast("lead", "Team standup: report progress", teamMgr.memberNames());
```

2. **TeamManager** spawns persistent teammates:

```java
TeamManager teamMgr = new TeamManager(teamDir, bus, client, model, workDir, sandbox);

dispatcher.register("spawn_teammate", input ->
    teamMgr.spawn(
        (String) input.get("name"),   // "alice"
        (String) input.get("role"),   // "backend"
        (String) input.get("prompt")  // initial instructions
    )
);
```

3. **Each teammate runs its own agent loop** in a Virtual Thread:

```java
// Inside TeamManager.spawn():
Thread.startVirtualThread(() -> {
    // Teammate has: own system prompt, own tools, own messages
    // Tools: bash, read_file, write_file, edit_file, send_message, read_inbox
    // Before each LLM call: drain own inbox
    teammateLoop(name, systemPrompt, tools, dispatcher);
});
```

4. **Subagent vs Teammate** -- the key difference:

```
Subagent (s04):  spawn -> execute -> return -> destroyed   (stateless)
Teammate (s09):  spawn -> work -> idle -> work -> shutdown  (stateful)
```

5. **REPL commands** for team management:

```
/team   -- list all teammates and status
/inbox  -- read lead's inbox messages
```

## What Changed

| Component       | s08                | s09                              |
|-----------------|--------------------|----------------------------------|
| Agent model     | Single agent       | Lead + persistent teammates      |
| Communication   | (none)             | `MessageBus` (JSONL file inboxes) |
| New tools       | (none)             | `spawn_teammate`, `list_teammates`, `send_message`, `read_inbox`, `broadcast` |
| Lifecycle       | (none)             | `TeamManager.spawn()`, Virtual Threads |
| New classes     | `BackgroundManager`| `MessageBus`, `TeamManager`, `Teammate` (record) |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S09AgentTeams"
```

1. `Spawn a teammate named "alice" with role "backend" to handle Java code`
2. `Send alice a message: "Create a UserService.java file"`
3. `/inbox` (check if alice replied)
4. `Broadcast to all teammates: "Report what you've done so far"`
5. `/team` (check teammate status)
