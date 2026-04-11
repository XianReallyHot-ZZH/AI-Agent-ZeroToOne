# s10: System Prompt Assembly

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > [ s10 ] s11 > s12`

> *"The system prompt is a pipeline with boundaries, not one giant string."*
>
> **Harness layer**: The prompt builder -- assembling context from multiple sources into a structured system prompt.

## Problem

A hardcoded system prompt works for demos, but real agents need context from many sources: available tools, skill metadata, user preferences, project instructions, and dynamic facts like the current date. Jamming everything into one string makes it impossible to test, cache, or evolve individual sections.

## Solution

```
  6 sections, assembled in order:
  +----+------------------------+----------+
  |  1 | Core instructions      | static   |
  |  2 | Tool listing           | static   |
  |  3 | Skill metadata         | static   |
  |  4 | Memory section         | static   |
  |  5 | CLAUDE.md chain        | static   |
  +----+------------------------+----------+
  |    | === DYNAMIC_BOUNDARY ===          |
  +----+------------------------+----------+
  |  6 | Dynamic context        | dynamic  |
  +----+------------------------+----------+

  Sections 1-5 are cacheable across turns.
  Section 6 is rebuilt every turn.
```

The `DYNAMIC_BOUNDARY` marker separates stable content from per-turn content. In production, the static prefix is cached to save prompt tokens.

## How It Works

### SystemPromptBuilder

```java
static class SystemPromptBuilder {
    private final Path workdir;
    private final List<Tool> tools;
    private final Path skillsDir;
    private final Path memoryDir;
    private final String model;

    public String build() {
        List<String> sections = new ArrayList<>();
        sections.add(_buildCore());           // 1. Identity
        sections.add(_buildToolListing());    // 2. Available tools
        sections.add(_buildSkillListing());   // 3. Skills from skills/
        sections.add(_buildMemorySection());  // 4. Memories from .memory/
        sections.add(_buildClaudeMd());       // 5. CLAUDE.md chain
        sections.add(DYNAMIC_BOUNDARY);       // --- boundary ---
        sections.add(_buildDynamicContext()); // 6. Date, platform, model
        return String.join("\n\n", sections);
    }
}
```

### Section 1: Core instructions

```java
private String _buildCore() {
    return "You are a coding agent operating in " + workdir + ".\n"
         + "Use the provided tools to explore, read, write, and edit files.\n"
         + "Always verify before assuming. Prefer reading files over guessing.";
}
```

### Section 2: Tool listing

```java
private String _buildToolListing() {
    List<String> lines = new ArrayList<>();
    lines.add("# Available tools");
    for (Tool tool : tools) {
        // Extract parameter names from InputSchema
        String params = String.join(", ", props.keySet());
        lines.add("- " + tool.name() + "(" + params + "): " + desc);
    }
    return String.join("\n", lines);
}
```

### Section 5: CLAUDE.md chain

```java
private String _buildClaudeMd() {
    // Load in priority order (all included, not mutually exclusive):
    // 1. ~/.claude/CLAUDE.md           -- user global instructions
    // 2. <project-root>/CLAUDE.md      -- project root instructions
    // 3. <current-subdir>/CLAUDE.md    -- subdirectory instructions
}
```

The chain loads all CLAUDE.md files that exist, merging user-global, project, and subdirectory instructions.

### Section 6: Dynamic context

```java
private String _buildDynamicContext() {
    lines.add("Current date: " + LocalDate.now().toString());
    lines.add("Working directory: " + workdir);
    lines.add("Model: " + model);
    lines.add("Platform: " + System.getProperty("os.name"));
    return "# Dynamic context\n" + String.join("\n", lines);
}
```

### Per-turn rebuild in the agent loop

```java
private static void agentLoop(..., SystemPromptBuilder promptBuilder) {
    while (true) {
        // Rebuild system prompt every turn
        String system = promptBuilder.build();
        paramsBuilder.system(system);

        Message response = client.messages().create(paramsBuilder.build());
        // ... rest of the loop is unchanged
    }
}
```

## What Changed

| Component     | Before              | After                                       |
|---------------|---------------------|---------------------------------------------|
| System prompt | Hardcoded string    | 6-section assembled pipeline                |
| Caching       | N/A                 | Static/dynamic boundary for future caching  |
| Tool context  | Implicit            | Explicit tool listing in prompt             |
| Skills        | Not in prompt       | SKILL.md frontmatter parsed and injected    |
| Memory        | Not in prompt       | `.memory/` content injected as section 4    |
| CLAUDE.md     | Not in prompt       | User-global + project + subdirectory chain  |
| Key classes   | (none)              | `SystemPromptBuilder`                       |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S10SystemPrompt"
```

1. `/prompt` -- display the full assembled system prompt
2. `/sections` -- show only section headers
3. Create a `CLAUDE.md` file in your project root with instructions
4. Run `/prompt` again to see your instructions included in section 5
5. Create `.memory/prefer_tabs.md` with frontmatter and verify it appears in section 4
