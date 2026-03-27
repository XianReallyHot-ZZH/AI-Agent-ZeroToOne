# s07: Task System

`s01 > s02 > s03 > s04 > s05 > s06 | [ s07 ] s08 > s09 > s10 > s11 > s12`

> *"State lives outside the conversation -- because it's on the filesystem."* -- tasks survive compression, restarts, and crashes.
>
> **Harness layer**: The task graph (DAG) -- persistent goals that outlive any single conversation.

## Problem

s03's `TodoManager` is in-memory. When the conversation compresses (s06) or the process restarts, the todo list is gone. You need tasks that persist to disk, support dependencies (task B can't start until task A finishes), and survive anything.

## Solution

```
.tasks/
  task_1.json   { id:1, subject:"Setup DB", status:"completed",
                  blockedBy:[], blocks:[2] }
  task_2.json   { id:2, subject:"Write API", status:"in_progress",
                  blockedBy:[1], blocks:[3] }
  task_3.json   { id:3, subject:"Add tests", status:"pending",
                  blockedBy:[2], blocks:[] }

  Completing task_1 --> auto-clears task_2.blockedBy
  Completing task_2 --> auto-clears task_3.blockedBy
```

Each task is a JSON file on disk. Dependencies form a DAG. Completing a task automatically unblocks its dependents.

## How It Works

1. **Task record** -- a Java 21 record for immutable task data:

```java
public record Task(
    int id, String subject, String description,
    String status, String owner,
    List<Integer> blockedBy, List<Integer> blocks
) {}
```

2. **TaskManager** persists tasks as individual JSON files in `.tasks/`:

```java
TaskManager taskMgr = new TaskManager(workDir.resolve(".tasks"));

// Create a task
taskMgr.create("Setup database", "Create schema and seed data");
// Returns: "Created task #1: Setup database [pending]"

// Create a dependent task
taskMgr.create("Write API", "CRUD endpoints for users");
// Returns: "Created task #2: Write API [pending]"
```

3. **Four tools** expose task operations to the model:

```java
// task_create -- new task with subject + description
dispatcher.register("task_create", input ->
    taskMgr.create((String) input.get("subject"),
                   (String) input.getOrDefault("description", "")));

// task_update -- change status, add dependencies
dispatcher.register("task_update", input ->
    taskMgr.update(taskId, status, addBlockedBy, addBlocks));

// task_list -- visual summary of all tasks
dispatcher.register("task_list", input -> taskMgr.listAll());

// task_get -- full details of a single task
dispatcher.register("task_get", input -> taskMgr.get(taskId));
```

4. **Dependency auto-resolution.** When `task_update` sets status to `completed`, the TaskManager automatically removes the completed task from all other tasks' `blockedBy` lists:

```java
// Inside TaskManager.update():
if ("completed".equals(status)) {
    _clearDependency(taskId);  // removes from all blockedBy arrays
}
```

5. **Why file-based?** The `.tasks/` directory survives context compression (s06), process restarts, and even crashes. The model can always `task_list` to recover the full state.

## What Changed

| Component       | s06                | s07                              |
|-----------------|--------------------|----------------------------------|
| State storage   | In-memory (TodoManager) | File-based `.tasks/` JSON   |
| Dependencies    | (none)             | `blockedBy` / `blocks` DAG       |
| Persistence     | Lost on restart    | Survives restarts and compression |
| Tools           | 7                  | +4: `task_create/update/list/get` |
| New classes     | (none)             | `Task` (record), `TaskManager`   |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S07TaskSystem"
```

1. `Create a task plan: setup project, write code, add tests (with dependencies)`
2. `Show me the task list`
3. `Mark the first task as completed`
4. `What tasks are now unblocked?`
