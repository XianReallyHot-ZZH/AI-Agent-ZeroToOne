# s03: TodoWrite

`s01 > s02 > [ s03 ] s04 > s05 > s06 | s07 > s08 > s09 > s10 > s11 > s12`

> *"An agent without a plan drifts."* -- force accountability with a structured todo list.
>
> **Harness layer**: The nag reminder -- keeping the model on track.

## Problem

Multi-step tasks fail silently. The model forgets step 3 while working on step 5. Without visible progress tracking, the model (and you) can't tell what's done and what's left. You need a structured plan the model updates as it works.

## Solution

```
+--------+      +-------+      +---------+
|  User  | ---> |  LLM  | ---> | Dispatch|
+--------+      +---+---+      +----+----+
                    ^               |
                    |               v
              +-----+----+   +------------+
              | TodoMgr  |   | Other tools|
              | [ ] task1|   +------------+
              | [>] task2|
              | [x] task3|
              +-----+----+
                    ^
                    | <reminder> (injected if 3+ rounds without todo update)
```

The `TodoManager` enforces a simple state machine: each item is `pending`, `in_progress`, or `completed`. Only one item can be `in_progress` at a time. If the model ignores the todo for too many rounds, a nag reminder is injected.

## How It Works

1. **Define a `todo` tool** with a structured schema. Each item has `id`, `text`, and `status` (enum: `pending`, `in_progress`, `completed`).

```java
AgentLoop.defineTool("todo",
    "Update task list. Track progress on multi-step tasks.",
    Map.of("items", Map.of(
        "type", "array",
        "items", Map.of(
            "type", "object",
            "properties", Map.of(
                "id",     Map.of("type", "string"),
                "text",   Map.of("type", "string"),
                "status", Map.of("type", "string",
                    "enum", List.of("pending", "in_progress", "completed"))),
            "required", List.of("id", "text", "status")))),
    List.of("items"))
```

2. **TodoManager** validates and stores the full list. Max 20 items, max 1 `in_progress`. `render()` produces visual output:

```java
TodoManager todo = new TodoManager();
// After update:
// [ ] Set up project structure
// [>] Create database schema   ← in_progress
// [ ] Write API endpoints
// [x] Install dependencies     ← completed
```

3. **The nag reminder** (concept). If the model goes 3+ rounds without calling `todo`, inject a reminder into the conversation:

```
<reminder>Update your todos. You haven't updated the task list recently.</reminder>
```

This forces the model to stay accountable to its own plan.

4. **Registration** -- the handler parses the items list and delegates to TodoManager:

```java
dispatcher.register("todo", input -> {
    List<?> items = (List<?>) input.get("items");
    return todo.update(items);
});
```

## What Changed

| Component       | s02                | s03                              |
|-----------------|--------------------|----------------------------------|
| Tools           | 4 (bash, read, write, edit) | +1: `todo`               |
| State tracking  | (none)             | `TodoManager` (in-memory)        |
| Nag reminder    | (none)             | Injected after 3 rounds idle     |
| Agent loop      | unchanged          | unchanged                        |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S03TodoWrite"
```

1. `Create a project with 3 source files and track your progress with the todo tool`
2. `Refactor the project to use packages -- plan first, then execute`
3. `Set up a test suite for the project, track each test file as a todo item`
