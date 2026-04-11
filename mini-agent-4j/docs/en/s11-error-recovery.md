# s11: Error Recovery

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > s10 > [ s11 ] s12`

> *"A robust agent recovers automatically instead of crashing."*
>
> **Harness layer**: Error recovery -- three strategies that keep the agent running when things go wrong.

## Problem

Real API calls fail. The model hits the max token limit. Context grows until the prompt is too long. Network connections drop. Without recovery, the agent crashes and the user loses all progress. A production agent must handle these failures gracefully.

## Solution

```
  LLM response
       |
       v
  [check stop_reason / error]
       |
       +-- max_tokens ---------> [Strategy 1: Continuation]
       |                          Inject: "Continue from where you stopped."
       |                          Retry up to 3 times.
       |
       +-- prompt_too_long -----> [Strategy 2: autoCompact]
       |                          Summarize conversation with LLM.
       |                          Replace history with summary.
       |                          Retry this turn.
       |
       +-- connection/rate -----> [Strategy 3: Backoff retry]
       |                          Exponential backoff: base * 2^attempt + jitter
       |                          Retry up to 3 times.
       |
       +-- end_turn ------------> [Normal exit]
```

## How It Works

### Strategy 1: max_tokens recovery

When the LLM output is truncated (`stopReason == MAX_TOKENS`), inject a continuation message:

```java
private static final String CONTINUATION_MESSAGE =
    "Output limit hit. Continue directly from where you stopped -- "
    + "no recap, no repetition. Pick up mid-sentence if needed.";

if (isMaxTokens) {
    maxOutputRecoveryCount++;
    if (maxOutputRecoveryCount <= MAX_RECOVERY_ATTEMPTS) {
        paramsHolder[0].addUserMessage(CONTINUATION_MESSAGE);
        continue;  // inject and retry
    } else {
        // Recovery exhausted after 3 attempts
        return;
    }
}
```

### Strategy 2: autoCompact for prompt_too_long

When the conversation exceeds the API's prompt limit, compress it:

```java
private static MessageCreateParams.Builder autoCompact(...) {
    // 1. Concatenate conversation log (last 80000 chars)
    String conversationText = String.join("\n", conversationLog);
    if (conversationText.length() > 80000)
        conversationText = conversationText.substring(conversationText.length() - 80000);

    // 2. Ask LLM to generate a structured summary
    String prompt = "Summarize this conversation for continuity. Include:\n"
            + "1) Task overview and success criteria\n"
            + "2) Current state: completed work, files touched\n"
            + "3) Key decisions and failed approaches\n"
            + "4) Remaining next steps\n";

    // 3. Replace entire history with summary continuation message
    String continuation = "This session continues from a previous conversation "
            + "that was compacted. Summary:\n\n" + summary;
    newBuilder.addUserMessage(continuation);

    // 4. Reset token estimate counter
    tokenEstimate[0] = continuation.length() / 4;
}
```

### Strategy 3: Exponential backoff with jitter

For transient API errors (connection, rate limiting):

```java
private static double backoffDelay(int attempt) {
    double delay = Math.min(BACKOFF_BASE_DELAY * Math.pow(2, attempt), BACKOFF_MAX_DELAY);
    double jitter = ThreadLocalRandom.current().nextDouble(0, 1);
    return delay + jitter;
}
```

Formula: `min(1.0 * 2^attempt, 30.0) + random(0, 1)`. The jitter prevents thundering herd when multiple clients retry simultaneously.

### Integrated recovery loop

```java
while (true) {
    Message response = null;

    // Outer: retry wrapper for API errors
    for (int attempt = 0; attempt <= MAX_RECOVERY_ATTEMPTS; attempt++) {
        try {
            response = client.messages().create(paramsHolder[0].build());
            break;  // success
        } catch (Exception e) {
            if (isPromptTooLong(e)) {
                // Strategy 2: compact and retry
                paramsHolder[0] = autoCompact(...);
                continue;
            }
            if (attempt < MAX_RECOVERY_ATTEMPTS) {
                // Strategy 3: backoff and retry
                Thread.sleep((long)(backoffDelay(attempt) * 1000));
                continue;
            }
            return;  // all retries exhausted
        }
    }

    // Strategy 1: max_tokens continuation
    if (stopReason == MAX_TOKENS) {
        paramsHolder[0].addUserMessage(CONTINUATION_MESSAGE);
        continue;
    }

    // Proactive auto-compact check
    if (isOverTokenThreshold(tokenEstimate)) {
        paramsHolder[0] = autoCompact(...);
    }
}
```

### Token estimation

```java
// Rough estimate: characters / 4 ~ tokens
tokenEstimate[0] += output.length() / 4;

// Proactive compact when estimate exceeds threshold (50000)
if (isOverTokenThreshold(tokenEstimate)) {
    autoCompact(...);  // compress before hitting the API limit
}
```

## What Changed

| Component       | Before          | After                                       |
|-----------------|-----------------|---------------------------------------------|
| max_tokens      | Agent crashes   | Continuation message injected, up to 3 retries |
| prompt_too_long | Agent crashes   | autoCompact summarizes and replaces history |
| API errors      | Agent crashes   | Exponential backoff with jitter, up to 3 retries |
| Token tracking  | (none)          | Rough char/4 estimate with proactive compact |
| Key classes     | (none)          | `autoCompact()`, `backoffDelay()`           |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S11ErrorRecovery"
```

1. Give the agent a very long task to see max_tokens recovery in action
2. Watch for `[Recovery] max_tokens hit` messages with continuation injection
3. On a slow network, observe `[Recovery] API error` with backoff retry
4. The agent will never crash -- it always recovers or reports failure gracefully
