# s07: Permission System

`s01 > s02 > s03 > s04 > s05 > s06 | [ s07 ] s08 > s09 > s10 > s11 > s12`

> *"Security is a pipeline, not a boolean."*
>
> **Harness layer**: The permission gate -- every tool call passes through a multi-stage check before execution.

## Problem

An agent with unrestricted tool access can destroy your filesystem, leak secrets, or run destructive commands. You could wrap every tool in ad-hoc checks, but that scatters security logic and makes it impossible to audit. You need a single, inspectable pipeline that governs all tool calls.

## Solution

```
  tool call
      |
      v
  [ BashSecurityValidator ]  <-- regex-based dangerous pattern detector
      |
      v
  [ 1. deny rules ]          <-- always checked first, cannot be bypassed
      |
      v
  [ 2. mode check ]          <-- default / plan / auto
      |
      v
  [ 3. allow rules ]         <-- explicit allow patterns
      |
      v
  [ 4. ask user ]            <-- no rule matched, interactive prompt
```

The pipeline is ordered by priority: deny rules are absolute, mode acts as a coarse filter, allow rules whitelist known-safe operations, and anything unmatched escalates to the user.

## How It Works

### Three permission modes

```java
/** Permission modes: default (ask), plan (read-only), auto (auto-approve reads) */
private static final List<String> MODES = List.of("default", "plan", "auto");

/** Read-only tools -- allowed in plan mode, auto-approved in auto mode */
private static final Set<String> READ_ONLY_TOOLS = Set.of("read_file");

/** Write tools -- blocked in plan mode */
private static final Set<String> WRITE_TOOLS = Set.of("write_file", "edit_file", "bash");
```

- **default**: No rule matched? Ask the user interactively.
- **plan**: Block all write tools, allow all read tools. For safe exploration.
- **auto**: Auto-approve read tools, ask for write tools. For trusted workflows.

### Bash security validator

```java
static class BashSecurityValidator {
    private static final List<AbstractMap.SimpleEntry<String, Pattern>> VALIDATORS = List.of(
        new AbstractMap.SimpleEntry<>("shell_metachar", Pattern.compile("[;&|`$]")),
        new AbstractMap.SimpleEntry<>("sudo",          Pattern.compile("\\bsudo\\b")),
        new AbstractMap.SimpleEntry<>("rm_rf",         Pattern.compile("\\brm\\s+(-[a-zA-Z]*)?r")),
        new AbstractMap.SimpleEntry<>("cmd_substitution", Pattern.compile("\\$\\(")),
        new AbstractMap.SimpleEntry>("ifs_injection",  Pattern.compile("\\bIFS\\s*="))
    );

    public List<String[]> validate(String command) {
        List<String[]> failures = new ArrayList<>();
        for (var validator : VALIDATORS) {
            if (validator.getValue().matcher(command).find()) {
                failures.add(new String[]{validator.getKey(), validator.getValue().pattern()});
            }
        }
        return failures;
    }
}
```

The validator catches dangerous patterns like `sudo`, `rm -rf`, and shell metacharacters. Severe patterns (sudo, rm_rf) are denied outright; other flags escalate to user confirmation.

### Permission pipeline check

```java
Map<String, String> check(String toolName, Map<String, Object> toolInput,
                          BashSecurityValidator bashValidator) {
    // Step 0: Bash security validation (severe patterns -> deny, others -> ask)
    if ("bash".equals(toolName)) {
        List<String[]> failures = bashValidator.validate(command);
        if (bashValidator.hasSevereFailure(failures)) {
            return Map.of("behavior", "deny", "reason", "Bash validator: " + desc);
        }
    }
    // Step 1: deny rules (unbypassable)
    // Step 2: mode-based decision (plan blocks writes, auto approves reads)
    // Step 3: allow rules
    // Step 4: ask user (default fallback)
}
```

### Interactive user approval with circuit breaker

```java
boolean askUser(String toolName, Map<String, Object> toolInput) {
    System.out.print("  Allow? (y/n/always): ");
    String answer = scanner.nextLine().trim().toLowerCase();

    if ("always".equals(answer)) {
        rules.add(Map.of("tool", toolName, "path", "*", "behavior", "allow"));
        return true;
    }
    // Circuit breaker: warn after 3 consecutive denials
    consecutiveDenials++;
    if (consecutiveDenials >= maxConsecutiveDenials) {
        System.out.println("[Consider switching to plan mode]");
    }
}
```

The "always" answer permanently adds an allow rule. The circuit breaker warns after repeated denials, suggesting plan mode.

## What Changed

| Component        | Before           | After                                        |
|------------------|------------------|----------------------------------------------|
| Tool execution   | Direct dispatch  | Dispatch gated by permission pipeline        |
| Security         | Blacklist only   | Pipeline: validator + deny + mode + allow + ask |
| Modes            | (none)           | default / plan / auto                        |
| User interaction | (none)           | y/n/always prompts, circuit breaker          |
| Key classes      | (none)           | `BashSecurityValidator`, `PermissionManager` |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S07PermissionSystem"
```

1. Select a mode (try `plan` first for safe read-only exploration)
2. `List all files in this directory` -- allowed in all modes
3. `Create a file called test.txt` -- blocked in plan mode, asks in default
4. `/mode auto` -- switch to auto mode at runtime
5. `/rules` -- inspect the current permission rule set
6. Try `Run sudo apt update` -- denied by the validator
