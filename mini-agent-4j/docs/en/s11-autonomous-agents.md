# s11: Autonomous Agents

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > [ s11 ] s12`

> *"Autonomy = do work if there is work, wait if there isn't."* -- Lead creates, teammates claim.
>
> **Harness layer**: The idle poll + task claiming -- teammates that find work themselves.

## Problem

s10's teammates wait for explicit instructions. The lead has to assign every task manually: "alice, do task 1. bob, do task 2." This doesn't scale. You want teammates that can *pull* tasks from a shared pool instead of waiting to be *pushed* assignments.

## Solution

```
Lead:                    Task Pool (.tasks/)              Teammates:
  |                     +-----------+                       |
  |-- task_create --->  | Task #1   | [pending, no owner]   |
  |-- task_create --->  | Task #2   | [pending, no owner]   |
  |                     +-----------+                       |
  |                          |                              |
  |                     (teammates poll)                     |
  |                          |                              |
  |                     +----+----+                         |
  |                     |         |                         |
  |                  alice      bob                        |
  |                  claim #1   claim #2                   |
  |                  (owner=alice) (owner=bob)              |
  |                                                       |
  |                  [execute]   [execute]                 |
  |                  completed   completed                 |
```

The lead creates tasks. Teammates autonomously poll the `.tasks/` directory for unclaimed work and claim it.

## How It Works

1. **Task tools are shared** between lead and teammates. The lead creates tasks; teammates claim and update them:

```java
// Lead creates tasks
dispatcher.register("task_create", input ->
    taskMgr.create((String) input.get("subject"),
                   (String) input.getOrDefault("description", "")));

// Teammates list tasks to find available work
dispatcher.register("task_list", input -> taskMgr.listAll());
```

2. **The `idle` tool** triggers the poll logic. When a teammate has nothing to do, it calls `idle`, which checks for unclaimed tasks:

```java
dispatcher.register("idle", input -> {
    String taskList = taskMgr.listAll();
    if (taskList.contains("[ ]")) {  // [ ] = pending, unclaimed
        return "There are pending tasks available. Use claim_task to pick one.\n"
            + taskList;
    }
    return "No pending tasks. Waiting...";
});
```

3. **The `claim_task` tool** sets ownership and status:

```java
dispatcher.register("claim_task", input ->
    taskMgr.claim(
        ((Number) input.get("task_id")).intValue(),
        (String) input.get("owner"))
);
// Sets: owner = "alice", status = "in_progress"
```

4. **Teammate loop has two phases** (in TeamManager):

```
WORK phase:  execute assigned tasks, update status to completed
IDLE phase:  call idle tool -> poll for pending tasks -> claim_task -> back to WORK
```

5. **REPL commands** for observability:

```
/team    -- list all teammates
/inbox   -- check lead's inbox
/tasks   -- show task pool status
```

## What Changed

| Component       | s10                | s11                              |
|-----------------|--------------------|----------------------------------|
| Task claiming   | Manual assignment  | Autonomous (teammates poll + claim) |
| New tools       | (none)             | `idle`, `claim_task`             |
| Task tools      | (none)             | `task_create`, `task_list`, `task_get`, `task_update` |
| Teammate behavior | Wait for instructions | Poll for work + auto-claim    |
| REPL commands   | `/team`, `/inbox`  | +1: `/tasks`                     |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S11AutonomousAgents"
```

1. `Spawn two teammates: alice (backend) and bob (testing)`
2. `Create tasks: "Write UserService", "Add unit tests for UserService", "Write integration tests"`
3. `/tasks` (watch as teammates claim tasks autonomously)
4. `/inbox` (check for progress messages from teammates)
