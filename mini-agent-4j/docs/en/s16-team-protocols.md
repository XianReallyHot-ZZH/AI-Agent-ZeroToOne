# s10: Team Protocols

`s01 > s02 > s03 > s04 > s05 > s06 | s07 > s08 > s09 > [ s10 ] s11 > s12`

> *"Protocol = message types + request_id correlation + state machine tracking."* -- one FSM pattern, two applications.
>
> **Harness layer**: The handshake -- structured request-response between lead and teammates.

## Problem

s09's `send_message` is unstructured. When the lead wants to shut down a teammate, it sends a message and hopes the teammate acts on it. There's no confirmation, no way to track whether the request was approved or rejected. You need structured handshakes with correlation IDs.

## Solution

```
Shutdown Protocol:
  Lead                                     Teammate
    |                                         |
    |--- shutdown_request(req_id: uuid-1) --->|
    |                                         | (decides: approve or reject)
    |<-- shutdown_response(req_id: uuid-1) ---|
    |                                         |
    |  (Lead matches response by req_id)      |

Plan Approval Protocol:
  Teammate                                 Lead
    |                                         |
    |--- plan_request(req_id: uuid-2) ------->|
    |                                         | (reviews plan)
    |<-- plan_approval(req_id: uuid-2) -------|
    |                                         |
    |  (Teammate checks response by req_id)   |
```

Both protocols share a common FSM: `pending -> approved | rejected`. Correlation is via matching `request_id`.

## How It Works

1. **TeamProtocol** tracks pending requests in `ConcurrentHashMap`s:

```java
TeamProtocol protocol = new TeamProtocol(bus);

// Shutdown request -- Lead initiates
dispatcher.register("shutdown_request", input ->
    protocol.requestShutdown((String) input.get("teammate"))
);
// Returns: "Shutdown request sent to alice (req_id: uuid-1234)"

// Check shutdown status -- Lead checks
dispatcher.register("shutdown_response", input ->
    protocol.checkShutdownStatus((String) input.get("request_id"))
);
// Returns: "alice: approved" or "alice: pending"
```

2. **Plan approval** -- Teammate submits, Lead reviews:

```java
// Lead reviews a teammate's plan
dispatcher.register("plan_approval", input ->
    protocol.reviewPlan(
        (String) input.get("request_id"),
        Boolean.TRUE.equals(input.get("approve")),
        (String) input.get("feedback"))
);
// Approve: "Plan approved for req_id uuid-5678"
// Reject:  "Plan rejected: needs more test coverage"
```

3. **Request ID correlation.** Each request gets a UUID. The response carries the same UUID, enabling async correlation:

```java
// Inside TeamProtocol:
String requestId = UUID.randomUUID().toString();
pendingShutdowns.put(requestId, Map.of(
    "teammate", teammateName,
    "status", "pending"
));
```

4. **Messages travel through the MessageBus.** The protocol uses the same `bus.send()` from s09, but with structured message types:

```
{ type: "shutdown_request", request_id: "uuid-1", from: "lead" }
{ type: "shutdown_response", request_id: "uuid-1", approved: true }
{ type: "plan_request", request_id: "uuid-2", plan: "..." }
{ type: "plan_approval", request_id: "uuid-2", approved: false, feedback: "..." }
```

## What Changed

| Component       | s09                | s10                              |
|-----------------|--------------------|----------------------------------|
| Communication   | Unstructured messages | Structured protocols (shutdown, plan) |
| Correlation     | (none)             | `request_id` (UUID)              |
| State tracking  | (none)             | `ConcurrentHashMap` pending requests |
| New tools       | 5 (team tools)     | +3: `shutdown_request`, `shutdown_response`, `plan_approval` |
| New class       | (none)             | `TeamProtocol`                   |

## Try It

```sh
cd mini-agent-4j
mvn compile exec:java -Dexec.mainClass="com.example.agent.sessions.S10TeamProtocols"
```

1. `Spawn a teammate named "alice" with role "backend"`
2. `Send alice a task, then request a graceful shutdown`
3. `Check the shutdown request status`
4. `Wait for alice to submit a plan, then review it`
