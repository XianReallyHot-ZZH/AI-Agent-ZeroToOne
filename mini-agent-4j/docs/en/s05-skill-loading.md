# s05: Skill Loading

`s01 > s02 > s03 > s04 > [ s05 ] s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"Don't stuff everything into the system prompt. Load on demand."* -- two-layer injection saves 80%+ tokens.
>
> **Harness layer**: The `load_skill` tool -- knowledge on demand.

## Problem

You want the agent to know about specialized topics (database migrations, Docker deployment, security best practices), but stuffing all that knowledge into the system prompt wastes tokens and dilutes focus. A 10-skill system prompt would burn 20,000 tokens before the user even speaks.

## Solution

```
Layer 1 (cheap, ~100 tokens/skill):        Layer 2 (on-demand, ~2000 tokens/skill):
+---------------------------+              +---------------------------+
| System Prompt             |              | load_skill("docker")      |
| Skills available:         |              |   --> full skill body     |
| - docker: Deploy with ... |   ------->   |   --> returned via        |
| - security: Audit ...     |              |       tool_result         |
| - testing: Configure ...  |              +---------------------------+
+---------------------------+
  (always loaded)                            (loaded only when called)
```

Skill names go in the system prompt (cheap). Full skill bodies are loaded via a `load_skill` tool call (expensive, but only when needed).

## How It Works

1. **SkillLoader scans `skills/*/SKILL.md` files.** Each file has YAML frontmatter with name, description, and trigger conditions:

```
skills/
  docker/SKILL.md       -- Docker deployment knowledge
  security/SKILL.md     -- Security audit knowledge
  testing/SKILL.md      -- Testing configuration knowledge
```

2. **Layer 1: Descriptions in system prompt.** `SkillLoader.getDescriptions()` returns short metadata for all skills:

```java
SkillLoader skillLoader = new SkillLoader(workDir.resolve("skills"));

String systemPrompt = "You are a coding agent.\n"
    + "Use load_skill to access specialized knowledge.\n\n"
    + "Skills available:\n" + skillLoader.getDescriptions();
```

Output injected into system prompt:
```
Skills available:
- docker: Deploy containers with Docker Compose and multi-stage builds
- security: Audit dependencies and fix OWASP Top 10 vulnerabilities
- testing: Configure JUnit 5, Mockito, and integration test suites
```

3. **Layer 2: Full content on demand.** When the model calls `load_skill("docker")`, the full skill body is returned via `tool_result`:

```java
dispatcher.register("load_skill",
    input -> skillLoader.getContent((String) input.get("name")));
```

4. **Token economics.** With 10 skills: Layer 1 costs ~1,000 tokens (always). Layer 2 costs ~2,000 tokens per skill, but only for skills actually used (typically 1-2 per session). Total savings: 80%+ compared to stuffing everything upfront.

## What Changed

| Component       | s04                | s05                              |
|-----------------|--------------------|----------------------------------|
| Tools           | 5 (parent) + 4 (child) | +1: `load_skill`            |
| Knowledge       | System prompt only | Two-layer: descriptions + on-demand |
| System prompt   | Static text        | Static + `skillLoader.getDescriptions()` |
| New class       | (none)             | `SkillLoader`                    |

## Try It

```sh
cd mini-agent-4j
mkdir -p skills/docker skills/testing
# Create SKILL.md files in each directory first
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S05SkillLoading"
```

1. `Load the docker skill and tell me how to deploy this project`
2. `I need to write tests -- load the testing skill first`
3. `What skills do you have available?`
