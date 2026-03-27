# s08: Background Tasks

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > [ s08 ] s09 > s10 > s11 > s12`

> *"Fire and forget -- the agent never blocks waiting for a command."* -- Virtual Threads make parallelism trivial.
>
> **Harness layer**: The notification queue -- async results delivered to a single-threaded loop.

## Problem

Some commands take minutes: `mvn clean install`, `docker build`, `npm install`. If the agent blocks on each one, it can't do anything else while waiting. You want the agent to fire off slow commands, continue thinking, and collect results when they're ready.

## Solution

```
Agent ----[background_run("mvn install")]----[background_run("npm install")]----[other work]----
              |                                    |
              v                                    v
         [Virtual Thread A]                  [Virtual Thread B]
              |                                    |
              +--> LinkedBlockingQueue <-- completed notifications <--+
                                      |
                                      v
                              [drain before next LLM call]
                              [inject results into conversation]
```

Commands run in Java 21 Virtual Threads (lightweight, non-blocking). Completion notifications land in a thread-safe queue. The agent loop drains the queue before each LLM call.

## How It Works

1. **BackgroundManager** wraps command execution in Virtual Threads:

```java
BackgroundManager bg = new BackgroundManager(workDir);

// Returns task_id immediately
dispatcher.register("background_run", input ->
    bg.run((String) input.get("command"), 120)  // 120s timeout
);
// Output: "Started bg task: mvn-1709123456 (running in background)"
```

2. **Inside `bg.run()`** -- a Virtual Thread is spawned:

```java
public String run(String command, int timeout) {
    String taskId = generateTaskId(command);
    Thread.startVirtualThread(() -> {
        String result = BashTool.execute(command, workDir);
        notificationQueue.offer(Map.of(
            "task_id", taskId,
            "status", "completed",
            "result", result
        ));
    });
    return "Started bg task: " + taskId;
}
```

3. **Drain notifications** before each REPL iteration:

```java
while (true) {
    var notifs = bg.drainNotifications();
    if (!notifs.isEmpty()) {
        for (var n : notifs) {
            System.out.println("[bg:" + n.get("task_id") + "] "
                + n.get("status") + ": " + n.get("result"));
        }
    }
    // ... then prompt user ...
}
```

4. **Check status** with `check_background`:

```java
dispatcher.register("check_background", input ->
    bg.check((String) input.get("task_id")));
// Output: "mvn-1709123456: COMPLETED (exit 0, 12s)"
```

5. **The loop stays single-threaded.** Only subprocess I/O is parallelized via Virtual Threads. The LLM calls remain sequential.

## What Changed

| Component       | s07                | s08                              |
|-----------------|--------------------|----------------------------------|
| Execution model | Blocking only      | Blocking + background (Virtual Threads) |
| New tools       | (none)             | `background_run`, `check_background` |
| Notification    | (none)             | `LinkedBlockingQueue`            |
| New class       | `TaskManager`      | `BackgroundManager`              |
| REPL hook       | (none)             | `drainNotifications()` before prompt |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S08BackgroundTasks"
```

1. `Run "mvn dependency:resolve" in the background`
2. `While that's running, list the project files`
3. `Check the background task status`
4. `Run "find . -name '*.java'" in the background and continue working`
