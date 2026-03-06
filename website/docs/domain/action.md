---
title: Action
description: Condition-driven units of work that fire at Window state transitions
sidebar_position: 2
---

# Action

An **Action** is a unit of work executed when a specific condition is met during a [Window](./window) lifecycle. Actions are the *what* to the Window's *when* — they decouple the trigger condition from the business operation being performed.

A [Profile](./profile) owns an ordered list of Actions. Each Action declares its **condition** (when to fire), its **type** (what to do), and its **configuration** (how to do it).

---

## Action Conditions

### Condition Reference

| Condition | Fires When | Typical Use Case |
|---|---|---|
| `ON_OPEN` | Window transitions from `PENDING → OPEN` | Setup tasks, notifications, pre-warm caches |
| `ON_CLOSING` | Window transitions from `OPEN → CLOSING` | Flush collected events, generate output files |
| `RECURRING_WHILE_OPEN` | Every `recurringInterval` while window is `OPEN` | Progress checkpoints, partial flushes, heartbeats |
| `ON_EMPTY_CLOSE` | Window closes with zero events collected | Alert on silent periods, generate empty ACK files |
| `ON_THRESHOLD_REACHED` | Event count crosses a configured threshold | Early flush before window closes, real-time alerts |
| `ON_ERROR` | Any upstream action in the chain fails | Dead-letter routing, ops notifications, compensating transactions |
| `ON_MANUAL_TRIGGER` | Explicit API call (`POST /windows/{id}/trigger`) | Reprocessing, operational overrides |
| `ON_DUPLICATE_DETECTED` | Deduplication rejects one or more events | Audit logging, source system notification |

### Condition Analysis

```
WINDOW OPEN ──────────────────────────────────────────► WINDOW CLOSED
     │                                                        │
     │ ON_OPEN                                   ON_CLOSING   │
     ▼                                                ▼       ▼
 ┌────────┐                                       ┌────────────────┐
 │ Setup  │   ◄── RECURRING_WHILE_OPEN ──►        │  Flush + Gen   │
 │ tasks  │      (every N min while open)         │  output files  │
 └────────┘                                       └────────────────┘
                        │
              ON_THRESHOLD_REACHED
                (event count > X)
                        │
                        ▼
              ┌──────────────────┐
              │  Early partial   │
              │  flush           │
              └──────────────────┘

    ON_EMPTY_CLOSE ──► fires only if 0 events at close time
    ON_ERROR       ──► fires if any action in chain throws
    ON_DUPLICATE   ──► fires when checksum collision detected
    ON_MANUAL      ──► fires only via explicit API call
```

---

## Action Types

### 1. `FILE_TO_EVENTS` — Inbound Transform

Parse a file arriving via an integration channel and push records as events into the platform.

```kotlin
data class FileToEventsAction(
    val specId: String,                        // FileSpec to parse against
    val integrationId: String,                 // Source SFTP / S3 / REST
    val onSuccess: PostProcessing = PostProcessing.ARCHIVE,
    val onFailure: PostProcessing = PostProcessing.QUARANTINE,
    val parallelism: Int = 1,
)

enum class PostProcessing { ARCHIVE, DELETE, QUARANTINE, MOVE }
```

**Typical conditions:** `ON_OPEN`, `RECURRING_WHILE_OPEN`

---

### 2. `EVENTS_TO_FILE` — Outbound Transform

Collect all events in the current window, run them through a FileSpec's `outbound` config, and deliver the generated file.

```kotlin
data class EventsToFileAction(
    val specId: String,                        // FileSpec with outbound config
    val integrationId: String,                 // Destination SFTP / S3 / Kafka
    val batchSize: Int = 10_000,               // Records per file
    val emptyFilePolicy: EmptyFilePolicy = EmptyFilePolicy.SKIP,
    val fileNamingOverride: String? = null,    // Override spec's fileNamingPattern
)

enum class EmptyFilePolicy {
    SKIP,             // Don't generate a file if no events
    GENERATE_EMPTY,   // Generate header-only file (useful for ACH/NACHA)
    GENERATE_TRAILER, // Generate with summary trailer only
}
```

**Typical conditions:** `ON_CLOSING`, `RECURRING_WHILE_OPEN` (partial flush)

---

### 3. `NOTIFY` — Notification Dispatch

Send a structured notification via email, Slack, PagerDuty, or a custom webhook.

```kotlin
data class NotifyAction(
    val channel: NotificationChannel,
    val template: String,            // Handlebars template ID or inline template
    val recipients: List<String>,
    val severity: NotificationSeverity = NotificationSeverity.INFO,
    // Template variables automatically injected:
    // {{windowId}}, {{profileId}}, {{eventCount}}, {{status}}, {{timestamp}}
)

enum class NotificationChannel { EMAIL, SLACK, PAGERDUTY, WEBHOOK, MS_TEAMS }
enum class NotificationSeverity { INFO, WARNING, CRITICAL }
```

**Typical conditions:** `ON_OPEN`, `ON_EMPTY_CLOSE`, `ON_ERROR`

---

### 4. `VALIDATE_COMPLETENESS` — Reconciliation Check

Assert that the number of events collected matches an expected count from a control record or external source. Blocks downstream actions if the check fails.

```kotlin
data class ValidateCompletenessAction(
    val expectedCountSource: ExpectedCountSource,
    val tolerance: ToleranceConfig = ToleranceConfig(),
    val onMismatch: MismatchPolicy = MismatchPolicy.BLOCK_AND_NOTIFY,
)

data class ExpectedCountSource(
    val type: SourceType,              // CONTROL_RECORD, DATABASE_QUERY, FIXED
    val integrationId: String? = null,
    val query: String? = null,         // SQL for DATABASE_QUERY type
    val fixedCount: Long? = null,
)

data class ToleranceConfig(
    val absoluteTolerance: Long = 0,   // Allow ± N records
    val percentTolerance: Double = 0.0 // Allow ± N%
)

enum class MismatchPolicy { BLOCK_AND_NOTIFY, WARN_AND_CONTINUE, FAIL_WINDOW }
```

**Typical conditions:** `ON_CLOSING` (before generating output)

---

### 5. `TRANSFORM_AND_ROUTE` — Multi-Destination Fan-out

Apply a FileSpec transformation and route the output to multiple destinations simultaneously (Kafka + SFTP + S3 in one action).

```kotlin
data class TransformAndRouteAction(
    val specId: String,
    val routes: List<RouteConfig>,
    val fanOutStrategy: FanOutStrategy = FanOutStrategy.PARALLEL,
)

data class RouteConfig(
    val integrationId: String,
    val filter: String? = null,        // SpEL expression to subset records
    val specIdOverride: String? = null // Different output format per destination
)

enum class FanOutStrategy {
    PARALLEL,    // Send to all destinations concurrently
    SEQUENTIAL,  // Send in order; stop on first failure
    BEST_EFFORT, // Send to all; continue even if some fail
}
```

**Typical conditions:** `ON_CLOSING`

---

### 6. `ARCHIVE` — Data Retention

Archive collected events to long-term storage (S3, GCS, ADLS) with configurable retention and compression.

```kotlin
data class ArchiveAction(
    val integrationId: String,          // S3 / GCS / ADLS integration
    val pathTemplate: String,           // e.g. "archives/{profileId}/{year}/{month}/{windowId}.gz"
    val compression: Compression = Compression.GZIP,
    val retentionDays: Int = 90,
    val format: ArchiveFormat = ArchiveFormat.JSONL,
)

enum class Compression { NONE, GZIP, ZSTD, SNAPPY }
enum class ArchiveFormat { JSONL, PARQUET, AVRO, CSV }
```

**Typical conditions:** `ON_CLOSING` (after transforms complete)

---

### 7. `INVOKE_EXTERNAL` — External System Callback

Call an external HTTP endpoint or Lambda function as a step in the action chain. Supports request templating, retry, and timeout.

```kotlin
data class InvokeExternalAction(
    val integrationId: String,              // REST integration
    val method: HttpMethod = HttpMethod.POST,
    val pathTemplate: String,               // e.g. "/api/batch/{windowId}/complete"
    val bodyTemplate: String? = null,       // Handlebars JSON template
    val expectedStatusCodes: Set<Int> = setOf(200, 201, 202),
    val retry: RetryConfig = RetryConfig(),
    val timeoutMs: Long = 30_000,
)

data class RetryConfig(
    val maxAttempts: Int = 3,
    val backoffMs: Long = 1_000,
    val backoffMultiplier: Double = 2.0,
)
```

**Typical conditions:** `ON_CLOSING`, `ON_ERROR`

---

## Action Domain Model

```kotlin
/**
 * A single executable step in a Profile's action chain.
 */
data class Action(
    val id: String,
    val profileId: String,
    val condition: ActionCondition,
    val type: ActionType,
    val config: ActionConfig,         // Sealed class — one of the types above
    val executionOrder: Int,          // Lower runs first within same condition
    val enabled: Boolean = true,
    val continueOnFailure: Boolean = false,  // If false, chain stops on error
    val timeoutMs: Long = 300_000,
    val tags: List<String> = emptyList(),
)

enum class ActionCondition {
    ON_OPEN,
    ON_CLOSING,
    RECURRING_WHILE_OPEN,
    ON_EMPTY_CLOSE,
    ON_THRESHOLD_REACHED,
    ON_ERROR,
    ON_MANUAL_TRIGGER,
    ON_DUPLICATE_DETECTED,
}

enum class ActionType {
    FILE_TO_EVENTS,
    EVENTS_TO_FILE,
    NOTIFY,
    VALIDATE_COMPLETENESS,
    TRANSFORM_AND_ROUTE,
    ARCHIVE,
    INVOKE_EXTERNAL,
}

sealed class ActionConfig
data class FileToEventsConfig(/* ... */)    : ActionConfig()
data class EventsToFileConfig(/* ... */)    : ActionConfig()
data class NotifyConfig(/* ... */)          : ActionConfig()
data class ValidateCompletenessConfig(/* */) : ActionConfig()
data class TransformAndRouteConfig(/* ... */) : ActionConfig()
data class ArchiveConfig(/* ... */)         : ActionConfig()
data class InvokeExternalConfig(/* ... */)  : ActionConfig()
```

---

## Action Execution Engine

```kotlin
interface ActionExecutor {
    /** Execute all actions for a given condition in [executionOrder] */
    suspend fun execute(
        condition: ActionCondition,
        window: WindowInstance,
        profile: Profile,
    ): List<ActionResult>
}

data class ActionResult(
    val actionId: String,
    val condition: ActionCondition,
    val type: ActionType,
    val status: ActionStatus,
    val startedAt: Instant,
    val completedAt: Instant,
    val recordsProcessed: Long = 0L,
    val error: String? = null,
    val retryCount: Int = 0,
)

enum class ActionStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED, TIMEOUT }
```

### Execution Flow

```
Profile.actions
    .filter { it.condition == currentCondition && it.enabled }
    .sortedBy { it.executionOrder }
    .forEach { action ->
        val result = executor.run(action, window)
        if (result.status == FAILED && !action.continueOnFailure) {
            // Trigger ON_ERROR chain, then stop
            executor.execute(ON_ERROR, window, profile)
            break
        }
    }
```

---

## Threshold Trigger Configuration

The `ON_THRESHOLD_REACHED` condition requires an additional configuration block on the `WindowConfig`:

```kotlin
data class ThresholdConfig(
    val eventCountThreshold: Long,         // Fire when N events collected
    val resetAfterTrigger: Boolean = true, // Re-arm after each trigger
    val triggerLimit: Int? = null,         // Max times to fire per window (null = unlimited)
)
```

Example — partial flush every 1,000 events:
```yaml
thresholds:
  - eventCountThreshold: 1000
    resetAfterTrigger: true
    triggerLimit: null   # unlimited partial flushes
```

---

## Related Concepts

- **[Window](./window)** — defines when conditions fire
- **[Profile](./profile)** — the aggregate root that owns Actions
- **[Integration Channels](./profile#integration-channels)** — targets for FILE_TO_EVENTS, EVENTS_TO_FILE, ARCHIVE
