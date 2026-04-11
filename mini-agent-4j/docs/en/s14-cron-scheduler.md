# s14: Cron Scheduler

`s01 > ... > s12 > s13 > [ s14 ] s15 > ... > s19`

> *"Scheduling remembers future work, then hands it back to the same main loop when the time arrives."*
>
> **Harness layer**: The scheduler -- cron-triggered prompts injected into the agent conversation.

## Problem

Some tasks are periodic: check build status every 5 minutes, generate a daily report at 9 AM, or clean up temporary files weekly. Without scheduling, you must remember to ask the agent each time. You need the agent to wake itself up.

## Solution

```
  +-------------------------------+
  |  Background thread            |
  |  (checks every second)        |
  |                               |
  |  For each task:               |
  |    if cronMatches(now):       |
  |      enqueue notification     |
  +-------------------------------+
               |
               v
  [notification_queue]
               |
        (drained at top of agentLoop)
               |
               v
  [injected as user message before LLM call]
```

Two persistence modes, two trigger modes:

```
  Persistence:                    Trigger:
  +-------------+------------+   +------------+------------------+
  | session     | Memory only |   | recurring  | Repeat until deleted |
  | durable     | Disk file   |   | one-shot   | Fire once, auto-delete |
  +-------------+------------+   +------------+------------------+
```

## How It Works

### Cron expression matching

```
  5-field cron expression:
  +-------+-------+-------+-------+-------+
  | min   | hour  | dom   | month | dow   |
  | 0-59  | 0-23  | 1-31  | 1-12  | 0-6   |
  +-------+-------+-------+-------+-------+

  Examples:
    */5 * * * *      -> every 5 minutes
    0 9 * * 1        -> every Monday at 9:00
    30 14 * * *      -> every day at 14:30
```

```java
public static boolean cronMatches(String expr, LocalDateTime dt) {
    String[] fields = expr.trim().split("\\s+");
    // Extract: minute, hour, day-of-month, month, day-of-week
    int[] values = {dt.getMinute(), dt.getHour(), dt.getDayOfMonth(),
                    dt.getMonthValue(), dt.getDayOfWeek().getValue() % 7};
    for (int i = 0; i < 5; i++) {
        if (!fieldMatches(fields[i], values[i], ranges[i][0], ranges[i][1]))
            return false;
    }
    return true;
}
```

Field matching supports: `*` (any), `*/N` (step), `N` (exact), `N-M` (range), `N,M` (list).

### CronScheduler: background checker + notification queue

```java
public static class CronScheduler {
    private final List<Map<String, Object>> tasks;
    private final ConcurrentLinkedQueue<String> notificationQueue;

    public String create(String cronExpr, String prompt,
                         boolean recurring, boolean durable) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        task.put("cron", cronExpr);
        task.put("prompt", prompt);
        task.put("recurring", recurring);
        task.put("durable", durable);
        if (recurring) task.put("jitter_offset", computeJitter(cronExpr));
        tasks.add(task);
        if (durable) saveDurable();
        return "Created task " + taskId;
    }

    public List<String> drainNotifications() {
        List<String> notifications = new ArrayList<>();
        String note;
        while ((note = notificationQueue.poll()) != null)
            notifications.add(note);
        return notifications;
    }
}
```

### Background check loop

```java
private void checkLoop() {
    while (!stopRequested.get()) {
        LocalDateTime now = LocalDateTime.now();
        int currentMinute = now.getHour() * 60 + now.getMinute();

        // Only check once per minute (avoid duplicate triggers)
        if (currentMinute != lastCheckMinute.get()) {
            lastCheckMinute.set(currentMinute);
            checkTasks(now);
        }
        TimeUnit.SECONDS.sleep(1);
    }
}
```

### Notification injection in the agent loop

```java
private static void agentLoop(..., CronScheduler scheduler) {
    while (true) {
        // Drain scheduled notifications before each LLM call
        List<String> notifications = scheduler.drainNotifications();
        for (String note : notifications) {
            paramsBuilder.addUserMessage(note);  // inject as user message
        }

        Message response = client.messages().create(paramsBuilder.build());
        // ... rest of the standard loop
    }
}
```

### Durable persistence

```java
// Save to .claude/scheduled_tasks.json
private void saveDurable() {
    List<Map<String, Object>> durable = tasks.stream()
        .filter(t -> Boolean.TRUE.equals(t.get("durable")))
        .collect(Collectors.toList());
    Files.writeString(SCHEDULED_TASKS_FILE, toJsonArray(durable));
}
```

### Jitter for recurring tasks

To avoid all agents firing at exactly :00 or :30:

```java
private int computeJitter(String cronExpr) {
    int minuteVal = Integer.parseInt(fields[0]);
    if (JITTER_MINUTES.contains(minuteVal)) {
        return (Math.abs(cronExpr.hashCode()) % 4) + 1;  // 1-4 minute offset
    }
    return 0;
}
```

### Auto-expiry

Recurring tasks older than 7 days are automatically removed:

```java
double ageDays = (nowEpoch - createdAt) / 86400.0;
if (recurring && ageDays > AUTO_EXPIRY_DAYS) {
    expired.add(taskId);
}
```

## What Changed

| Component   | Before         | After                                        |
|-------------|----------------|----------------------------------------------|
| Tools       | 4 standard     | 7 -- added `cron_create`, `cron_delete`, `cron_list` |
| Scheduling  | (none)         | Background thread + notification queue       |
| Persistence | (none)         | `.claude/scheduled_tasks.json` for durable tasks |
| Agent loop  | Standard       | Drains notifications before each LLM call    |
| Auto-cleanup| (none)         | One-shot auto-delete, recurring 7-day expiry |
| Key classes | (none)         | `CronScheduler`                              |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S14CronScheduler"
```

1. `Check git status every minute and report any uncommitted changes` -- the agent creates a cron task
2. `/cron` -- list all scheduled tasks
3. Wait for the next minute boundary to see the notification fire
4. `/test` -- manually inject a test notification to see injection work immediately
5. Tasks marked durable persist across restarts (check `.claude/scheduled_tasks.json`)
