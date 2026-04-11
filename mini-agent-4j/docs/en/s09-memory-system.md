# s09: Memory System

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > [ s09 ] s10 > s11 > s12`

> *"Memory stores only what survives across sessions and cannot be re-derived from the repo."*
>
> **Harness layer**: The memory system -- persistent knowledge that outlives any single conversation.

## Problem

Every conversation starts from zero. The agent cannot remember that you prefer tabs over spaces, that a legacy module must not be touched, or where the team dashboard lives. Without memory, you repeat yourself every session. But storing everything creates noise -- you need a filter that saves only what matters.

## Solution

```
  .memory/
    MEMORY.md          <- index file (200-line cap)
    prefer_tabs.md     <- single memory (frontmatter + body)
    review_style.md
    incident_board.md
```

Each memory is a Markdown file with YAML frontmatter. The index file provides a fast overview. A `save_memory` tool lets the LLM persist knowledge. A `DreamConsolidator` prevents memory from becoming noise.

## How It Works

### What to remember vs. what not to

```
  Remember:                              Don't remember:
  - User preferences ("I like tabs")     - File structure (derivable from code)
  - Repeated corrections ("don't do X")  - Temporary state (current branch)
  - Non-obvious project facts            - Secrets or credentials
  - External resource pointers (URLs)    - Anything re-derivable from the repo
```

### MemoryManager: load, save, inject

```java
static class MemoryManager {
    void loadAll() {
        // Scan .memory/*.md (skip MEMORY.md index)
        // Parse frontmatter from each file
        // Populate in-memory map: name -> {description, type, content, file}
    }

    String saveMemory(String name, String description, String memType, String content) {
        // 1. Validate type (user / feedback / project / reference)
        // 2. Sanitize name -> safe filename
        // 3. Write frontmatter format:
        //    ---
        //    name: prefer_tabs
        //    description: User prefers tabs over spaces
        //    type: user
        //    ---
        //    Always use tabs for indentation...
        // 4. Update in-memory map
        // 5. Rebuild MEMORY.md index (200-line cap)
    }

    String loadMemoryPrompt() {
        // Build Markdown section for system prompt injection
        // Grouped by type: [user], [feedback], [project], [reference]
    }
}
```

### Dynamic system prompt injection

```java
private static String buildSystemPrompt() {
    List<String> parts = new ArrayList<>();
    parts.add("You are a coding agent at " + WORK_DIR + ". Use tools to solve tasks.");

    // Inject current memories into the system prompt
    String memorySection = memoryMgr.loadMemoryPrompt();
    if (!memorySection.isEmpty()) {
        parts.add(memorySection);
    }

    // Inject memory usage guidance
    parts.add(MEMORY_GUIDANCE);
    return String.join("\n\n", parts);
}
```

The system prompt is rebuilt before every LLM call, so newly saved memories are visible immediately.

### The save_memory tool

```java
defineTool("save_memory", "Save a persistent memory that survives across sessions.",
    Map.of(
        "name",        Map.of("type", "string", "description", "Short identifier"),
        "description", Map.of("type", "string", "description", "One-line summary"),
        "type",        Map.of("type", "string", "enum", List.of("user","feedback","project","reference")),
        "content",     Map.of("type", "string", "description", "Full memory content")),
    List.of("name", "description", "type", "content"));
```

The model can call this tool to persist knowledge during a conversation.

### DreamConsolidator: preventing memory noise

```java
static class DreamConsolidator {
    // 7 gates (all must pass before consolidation runs):
    // 1. enabled flag
    // 2. memory directory exists and has files
    // 3. not in plan mode
    // 4. 24-hour cooldown since last consolidation
    // 5. 10-minute scan throttle
    // 6. minimum 5 sessions of data
    // 7. PID lock (prevent concurrent consolidation)

    // 4 phases:
    // 1. Orient: scan MEMORY.md index
    // 2. Gather: read individual memory files
    // 3. Consolidate: merge related, remove stale
    // 4. Prune: enforce 200-line index cap
}
```

The consolidator is an optional background process that keeps memories clean. It runs through 7 gates before starting and executes 4 phases to merge, deduplicate, and prune.

## What Changed

| Component      | Before             | After                                        |
|----------------|--------------------|----------------------------------------------|
| System prompt  | Static string      | Dynamic: rebuilt every LLM call with memories |
| Tools          | 4 (bash/read/write/edit) | 5 -- added `save_memory`              |
| Persistence    | (none)             | `.memory/` directory with frontmatter files  |
| Index          | (none)             | `MEMORY.md` auto-rebuilt (200-line cap)      |
| Memory types   | (none)             | user / feedback / project / reference        |
| Key classes    | (none)             | `MemoryManager`, `DreamConsolidator`         |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S09MemorySystem"
```

1. `I always use tabs for indentation in this project, not spaces` -- the agent should save this as a memory
2. `/memories` -- list all current memories
3. `What indentation style do I prefer?` -- the agent should recall from memory
4. Restart the session -- memories persist across restarts
5. Check `.memory/MEMORY.md` to see the index file
