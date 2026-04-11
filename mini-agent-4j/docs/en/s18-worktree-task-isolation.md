# s12: Worktree + Task Isolation

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > [ s12 ]`

> *"Isolate by directory, coordinate by task ID. The filesystem IS the isolation boundary."* -- parallel agents, zero file collisions.
>
> **Harness layer**: The worktree -- directory-level execution context bound to tasks.

## Problem

s11's autonomous teammates all work in the same directory. If alice and bob both edit `UserService.java`, the last write wins and someone's changes are lost. You need directory-level isolation so parallel agents can work on the same repo without file collisions.

## Solution

```
Control Plane (Tasks)           Execution Plane (Worktrees)
+-------------+                +---------------------+
| Task #1     | ----bound--->  | wt/auth-refactor    |
| Task #2     | ----bound--->  | wt/fix-tests        |
| Task #3     |                |   (unclaimed)       |
+-------------+                +---------------------+
       |                               |
  .tasks/task_1.json          .worktrees/wt-auth-refactor/
  .tasks/task_2.json             (git worktree = separate checkout)
  .tasks/task_3.json             (own branch, own working directory)

Binding: task_id <-> worktree name
Lifecycle: active -> kept | removed
Events:   .worktrees/events.jsonl (structured audit trail)
```

Tasks are the "control plane" (what to do). Worktrees are the "execution plane" (where to do it). They're bound by task ID.

## How It Works

1. **WorktreeManager** wraps git worktree operations:

```java
WorktreeManager wtMgr = new WorktreeManager(repoRoot);

// Auto-detect git repo root
Path repoRoot = detectRepoRoot();
// Executes: git rev-parse --show-toplevel
```

2. **Create a worktree** (optionally bound to a task):

```java
dispatcher.register("worktree_create", input ->
    wtMgr.create(
        (String) input.get("name"),       // "wt-auth-refactor"
        input.get("task_id") instanceof Number n ? n.intValue() : null,  // optional binding
        (String) input.get("base_ref")    // optional: "main", "HEAD~3"
    )
);
// Runs: git worktree add .worktrees/wt-auth-refactor -b wt-auth-refactor
```

3. **Execute commands inside a worktree**:

```java
dispatcher.register("worktree_run", input ->
    wtMgr.run(
        (String) input.get("name"),       // "wt-auth-refactor"
        (String) input.get("command")     // "mvn test"
    )
);
// Runs: cd .worktrees/wt-auth-refactor && <command>
```

4. **Lifecycle management** -- keep or remove:

```java
// Remove a worktree (deletes directory + branch)
dispatcher.register("worktree_remove", input ->
    wtMgr.remove((String) input.get("name"), force, false)
);
// Runs: git worktree remove .worktrees/wt-auth-refactor

// Keep a worktree (preserve for later review)
dispatcher.register("worktree_keep", input -> ...)
```

5. **Event stream** for audit trail:

```java
dispatcher.register("worktree_events", input ->
    wtMgr.recentEvents(limit)
);
// Output from .worktrees/events.jsonl:
// [2024-01-15T10:30:00] create.before: wt-auth-refactor (task_id=1)
// [2024-01-15T10:30:01] create.after: wt-auth-refactor created
// [2024-01-15T10:45:00] run: wt-auth-refactor "mvn test"
// [2024-01-15T11:00:00] remove.before: wt-auth-refactor
```

6. **REPL commands**:

```
/tasks      -- show task pool
/worktrees  -- list all worktrees with status
/events     -- show recent worktree lifecycle events
```

## What Changed

| Component       | s11                | s12                              |
|-----------------|--------------------|----------------------------------|
| Isolation       | Same directory     | Git worktree per task            |
| New tools       | (none)             | `worktree_create/list/status/run/remove/keep/events` (7 tools) |
| Binding         | (none)             | Task ID <-> Worktree name        |
| Event tracking  | (none)             | `.worktrees/events.jsonl`        |
| New class       | (none)             | `WorktreeManager`                |
| Repo detection  | (none)             | `detectRepoRoot()` via git CLI   |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S12WorktreeIsolation"
```

1. `Create a task "Refactor authentication" and a worktree bound to it`
2. `Create another task "Fix test failures" with its own worktree`
3. `Run "ls" inside the first worktree`
4. `/worktrees` (list all worktrees and their status)
5. `Remove the first worktree after reviewing its changes`
6. `/events` (check the lifecycle event log)
