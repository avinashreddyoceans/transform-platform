---
title: Window
description: Time-bounded event buckets that drive scheduled batch workflows
sidebar_position: 1
---

# Window

A **Window** is a time-bounded bucket for collecting events or triggering scheduled actions. It is the heartbeat of the Transform Platform's batch processing model — defining *when* work happens, *how long* a collection period lasts, and *what happens* at its boundaries.

Windows decouple event collection from processing: events accumulate inside an open window; actions fire when the window transitions state.

---

## Lifecycle

```
                  ┌──────────────────────────────────────────────────┐
                  │                 WINDOW LIFECYCLE                  │
                  └──────────────────────────────────────────────────┘

    ON_OPEN                              ON_CLOSING
       │                                     │
       ▼                                     ▼
  ┌─────────┐   events flow in    ┌──────────────────┐   closed
  │ OPENING │ ──────────────────► │     OPEN         │ ──────────► CLOSED
  └─────────┘                     │                  │
                                  │  RECURRING_WHILE  │
                                  │  _OPEN fires here │
                                  └──────────────────┘
                                         ▲  │
                                         └──┘ (repeats on interval)
```

| State | Description |
|---|---|
| `PENDING` | Defined but not yet scheduled |
| `OPENING` | Cron fired, window transitioning open |
| `OPEN` | Collecting events; `RECURRING_WHILE_OPEN` actions eligible |
| `CLOSING` | End cron fired; `ON_CLOSING` actions executing |
| `CLOSED` | All actions complete, events archived |
| `ERROR` | Transition failed; retry or dead-letter |

---

## Configuration Model

```kotlin
/**
 * Defines a time-bounded event collection window.
 * Attached to a [Profile] to form a complete batch workflow.
 */
data class WindowConfig(
    val id: String,                              // e.g. "payments-30min-window"
    val profileId: String,
    val frequency: WindowFrequency,
    val autoCreateDefaultWindow: Boolean = true, // open window even if no events arrive
    val maxDurationMinutes: Int? = null,         // safety cap — force-close if stuck
    val timezone: String = "UTC",                // IANA timezone for cron evaluation
    val metadata: Map<String, String> = emptyMap(),
)

data class WindowFrequency(
    /**
     * Cron expression (6-field Spring format) that OPENS the window.
     * Example: "0 0 */6 * * ?"  → Opens every 6 hours
     */
    val startCronExpression: String,

    /**
     * Cron expression that CLOSES the window and triggers ON_CLOSING actions.
     * For non-overlapping windows, start == end cron offset by duration.
     * Example: "0 30 */6 * * ?"  → Closes 30 min after each open
     */
    val endCronExpression: String,

    /**
     * For RECURRING_WHILE_OPEN actions — how often they fire while the window
     * is open. Uses ISO-8601 duration format.
     * Example: PT5M  → Every 5 minutes
     */
    val recurringInterval: java.time.Duration? = null,
)
```

### Example Configurations

**30-minute rolling window** — classic batch cut:
```yaml
frequency:
  startCronExpression: "0 0/30 * * * ?"    # Opens at :00 and :30
  endCronExpression:   "0 29/30 * * * ?"   # Closes at :29 and :59
  recurringInterval:   PT5M                # Heartbeat every 5 min while open
autoCreateDefaultWindow: true
```

**Daily end-of-business window:**
```yaml
frequency:
  startCronExpression: "0 0 8 * * MON-FRI ?"   # Opens 08:00 weekdays
  endCronExpression:   "0 0 17 * * MON-FRI ?"  # Closes 17:00 weekdays
autoCreateDefaultWindow: false   # Only create if events arrived
```

**Monthly settlement window:**
```yaml
frequency:
  startCronExpression: "0 0 0 1 * ?"     # Opens 1st of each month
  endCronExpression:   "0 59 23 L * ?"   # Closes last day of month
autoCreateDefaultWindow: true
```

---

## Window Runtime State

At runtime, each scheduled window creates a `WindowInstance` — a live record tracking collected events, state transitions, and audit metadata.

```kotlin
data class WindowInstance(
    val instanceId: UUID,
    val windowConfigId: String,
    val profileId: String,

    val state: WindowState,
    val openedAt: Instant?,
    val closedAt: Instant?,

    val eventCount: Long = 0L,
    val lastEventAt: Instant? = null,

    // Checksum registry — prevents duplicate events across windows
    val eventChecksums: Set<String> = emptySet(),

    val actionResults: List<ActionResult> = emptyList(),
    val error: String? = null,
)

enum class WindowState {
    PENDING, OPENING, OPEN, CLOSING, CLOSED, ERROR
}
```

---

## Service Interface

```kotlin
interface WindowService {

    /** Schedule all windows defined on a Profile */
    fun scheduleProfile(profileId: String)

    /** Manually open a window outside of its cron schedule (e.g. for reprocessing) */
    suspend fun openWindow(windowConfigId: String): WindowInstance

    /** Force-close a window (e.g. emergency flush) */
    suspend fun closeWindow(instanceId: UUID): WindowInstance

    /** Get current open instance for a window config, if any */
    fun getCurrentInstance(windowConfigId: String): WindowInstance?

    /** Stream events into the current open window */
    suspend fun collectEvent(windowConfigId: String, event: Map<String, Any>): CollectResult

    /** Paginated history of all instances */
    fun listInstances(windowConfigId: String, pageable: Pageable): Page<WindowInstance>
}

data class CollectResult(
    val instanceId: UUID,
    val accepted: Boolean,
    val reason: String? = null,   // e.g. "DUPLICATE_CHECKSUM", "WINDOW_CLOSED"
)
```

---

## Overlap and Gap Handling

Windows are designed to be **non-overlapping by default** but the platform supports flexible patterns:

| Pattern | Configuration | Use Case |
|---|---|---|
| **Non-overlapping** | End cron fires before next start cron | Standard batch cuts (30-min, hourly) |
| **Tumbling** | Start = End (same cron, full period) | Exactly-once semantics |
| **Sliding** | `maxDurationMinutes` + continuous start | Real-time approximation |
| **Gap windows** | Time between close and next open | Settlement cooling-off periods |

When `autoCreateDefaultWindow: true`, a `CLOSED` window with zero events is still recorded — ensuring audit completeness even for quiet periods.

---

## Deduplication

Every event collected into a window is fingerprinted with a SHA-256 checksum (default: over the raw event payload). Duplicate events arriving within the same window are rejected with `DUPLICATE_CHECKSUM`.

```kotlin
data class DeduplicationConfig(
    val enabled: Boolean = true,
    val strategy: DeduplicationStrategy = DeduplicationStrategy.PAYLOAD_HASH,
    val fields: List<String> = emptyList(),   // for FIELD_BASED strategy
)

enum class DeduplicationStrategy {
    PAYLOAD_HASH,    // SHA-256 over full event payload
    FIELD_BASED,     // hash over specific fields (e.g. transactionId + amount)
    NONE,            // disable (allow duplicates)
}
```

---

## Related Concepts

- **[Action](./action)** — units of work triggered at window state transitions
- **[Profile](./profile)** — the aggregate root that owns a `WindowConfig` and its `Actions`
- **[Events → File Pipeline](../events-to-file)** — the most common `ON_CLOSING` action
