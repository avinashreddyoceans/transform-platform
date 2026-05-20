# Transform Platform — Implementation Plan

> **Last updated:** 2026-03-24
> **Current focus:** Phase 2 — Integration Channels (Phase 0 foundation + Phases 1a–1d core engine complete)
> **How to use this doc:** Update "Current focus" and tick off tasks as they complete. Add decisions under each phase as they are made. This is the authoritative plan — `website/docs/roadmap.md` mirrors it.

---

## Ground Truth: Where We Stand

| Module | Status | What's built |
|---|---|---|
| `platform-core` | ✅ Done | CSV/Fixed-Width/XML parsers, CorrectionEngine, ValidationEngine, TransformationPipeline, KafkaRecordWriter, ParserRegistry, unit tests |
| `platform-api` | ✅ Done | ProfileController (full CRUD + enable/disable/validate/trigger), WindowController (list/get/open/close/reprocess/events), WorkflowExecutionController, JPA persistence layer, WorkflowOrchestratorImpl, WorkflowRecoveryService |
| `platform-common` | ⚠️ Partial | build.gradle.kts — domain types defined in platform-scheduler interfaces; JPA entities in platform-api persistence layer |
| `platform-scheduler` | ✅ Done | WindowSchedulingService, WindowStateService, WindowOpenJob, WindowCloseJob, WindowOrchestratorJob, WorkflowOrchestrator interface, TriggerEvaluators (Time/FileArrival/EventCount/Compound), WindowOpenChecker, WindowCloseChecker |
| `platform-integration` | ⚠️ Partial | SFTP/S3 archival, IntegrationEncryptionService (AES-256-GCM), ServiceIntegrationController — not yet wired to IntegrationChannel interface from Phase 2 |
| `platform-pipeline` | 🗑️ Remove | Spring Batch — wrong model, Window/Action replaces it (removal pending) |

### Status as of 2026-03-24
- ✅ Full Profile + Window REST API (25+ endpoints)
- ✅ Window state machine (PENDING → OPEN → CLOSING → CLOSED / FORCE_CLOSED / ERROR)
- ✅ WorkflowOrchestratorImpl with ParseFile, Validate, Notify step execution
- ✅ WorkflowRecoveryService (@PostConstruct crash recovery)
- ✅ Full observability stack (OTel, Jaeger, Prometheus, Grafana, Elasticsearch, Kibana)
- ✅ PostgreSQL persistence via schema.sql (not Flyway — single managed schema file)
- ⏳ Remaining: Flyway migrations, ActionResultEntity, ProfileRevisionEntity, StepExecutor interface refactor, RetryScheduler, DeduplicationService, EventIngestionService, Quartz JDBC persistence

---

## Architecture Overview

Before the phases, here is the full mental model of how everything fits together.

### The core idea

Every piece of work the platform does is driven by three layered concepts:

```
Profile  →  defines the rules and schedule for a data workflow
  Window   →  a time-bounded or trigger-bounded collection context
    Action   →  something that runs when the window opens, closes, or hits a threshold
      WorkflowStep[]  →  the ordered steps INSIDE an action (parse, validate, transform, notify, deliver...)
```

A **Profile** is config-only. It describes a client's workflow entirely in JSON — no code.
A **Window** is the runtime execution of that profile's schedule — it opens, collects data, and closes.
An **Action** is triggered by window lifecycle events (OPEN, CLOSING, THRESHOLD, ERROR).
A **WorkflowStep** is an individual step inside an action — parse a file, validate records, notify over Kafka, etc.

### The three data flow directions

```
Direction 1: FILE → EVENTS
  File arrives (SFTP / S3 / upload)
    → PARSE_FILE step (FileParser + FileSpec)
    → VALIDATE step
    → CORRECT step
    → MAP_TO_NOTIFICATION step
    → NOTIFY step  (HTTP / Kafka / MessageQueue / ...)

Direction 2: EVENTS → FILE
  Window collects events (from Kafka / REST / DB / ...)
    → DEDUPLICATE step
    → MERGE_SOURCES step (optionally merge with an arrived file)
    → CORRECT step
    → VALIDATE step
    → GENERATE_FILE step (FileGenerator + FileSpec)
    → DELIVER_FILE step  (SFTP / S3 / ...)

Direction 3: EVENTS + FILE → FILE  (hybrid)
  Window collects events AND waits for a file to arrive
    → Compound trigger: window only closes when BOTH conditions are met
    → PARSE_FILE step  (parse the arrived file into records)
    → MERGE_SOURCES step  (merge file records with window events)
    → GENERATE_FILE step
    → DELIVER_FILE step
```

### Module layout (target)

```
platform-common/        ← shared JPA entities, domain events, DTOs, interfaces, exceptions  (no Spring)
platform-core/          ← pure transformation engine (parsers, correction, validation)  ✅ DONE
platform-integration/   ← pluggable channels: SFTP, S3, Kafka, DB, REST
platform-scheduler/     ← window scheduler, workflow orchestration engine, Quartz jobs
platform-api/           ← Spring Boot REST gateway (thin controllers, delegates to services)
```

Dependency graph (no cycles):
```
platform-api
  ├── platform-scheduler
  │     ├── platform-integration
  │     │     └── platform-common
  │     └── platform-common
  └── platform-core
        └── platform-common
```

### Future microservice decomposition

The module boundaries above are designed so that each module can become an independent deployable service later with minimal rework. The rules that make this possible:

1. **No direct method calls across future service boundaries** — all cross-module communication uses domain events (Kafka) or explicit API contracts (REST/gRPC). Today we use Spring beans inside a monolith; tomorrow we swap the bean call for a Kafka producer/consumer.
2. **Each module owns its DB schema namespace** — even in the shared PostgreSQL, tables are prefixed by module (`core_*`, `sched_*`, `intg_*`). No cross-module foreign keys.
3. **`platform-common` has no Spring dependencies** — it is a plain Kotlin library. All modules can depend on it without pulling in a web server.
4. **Domain events are the integration contract** — `ProfileEnabled`, `WindowOpened`, `WindowClosed`, `FileArrived`, `WorkflowCompleted` etc. are Kotlin data classes in `platform-common`. Today they travel in-process; later they travel over Kafka.

---

## Domain Model Deep Dive

### Profile

```
Profile {
  id, name, clientId, description
  status: DRAFT | ENABLED | DISABLED
  version: Int                         // bumped on every PUT
  windowConfig: WindowConfig           // JSONB
  actions: List<Action>                // owned collection
  tags: Map<String, String>
  createdAt, updatedAt
}
```

### WindowConfig

```
WindowConfig {
  openTrigger:  WindowTrigger          // when to open
  closeTrigger: WindowTrigger          // when to close
  recurringInterval: Duration?         // if set, fires RECURRING_WHILE_OPEN actions
  maxOpenDuration: Duration?           // safety: force-close if window stays open too long
  allowEmptyClose: Boolean             // whether to fire ON_CLOSING when eventCount == 0
  deduplicationEnabled: Boolean
  timeZone: ZoneId
}
```

### WindowTrigger (compound triggers)

This is the key to supporting all three data flow patterns.

```
WindowTrigger {
  type: TIME_BASED | FILE_ARRIVAL | EVENT_COUNT | COMPOUND_AND | COMPOUND_OR | MANUAL

  // for TIME_BASED
  cronExpression: String?

  // for FILE_ARRIVAL
  integrationId: String?               // which SFTP/S3 integration to watch
  filePattern: String?                 // glob pattern e.g. "payments_*.csv"

  // for EVENT_COUNT
  threshold: Int?                      // close when N events collected

  // for COMPOUND_AND / COMPOUND_OR
  subTriggers: List<WindowTrigger>?    // all must fire (AND) or any (OR)
}
```

**Examples:**

```json
// Pattern 1 — close at 17:00 every weekday
{ "type": "TIME_BASED", "cronExpression": "0 17 * * MON-FRI" }

// Pattern 2 — close when file arrives on SFTP (event-driven, not time-driven)
{ "type": "FILE_ARRIVAL", "integrationId": "acme-sftp-inbound", "filePattern": "payments_*.csv" }

// Pattern 3 — hybrid: close only when BOTH 17:00 has passed AND a file has arrived
{
  "type": "COMPOUND_AND",
  "subTriggers": [
    { "type": "TIME_BASED", "cronExpression": "0 17 * * MON-FRI" },
    { "type": "FILE_ARRIVAL", "integrationId": "acme-sftp-inbound", "filePattern": "payments_*.csv" }
  ]
}

// Pattern 4 — close as soon as 500 events accumulate (high-volume micro-batching)
{ "type": "EVENT_COUNT", "threshold": 500 }
```

### Action

```
Action {
  id, profileId, name
  condition: ON_OPEN | ON_CLOSING | ON_EMPTY_CLOSE | RECURRING_WHILE_OPEN | ON_THRESHOLD_REACHED | ON_ERROR | ON_FILE_ARRIVED
  executionOrder: Int                  // actions with the same condition run in this order
  continueOnFailure: Boolean
  steps: List<WorkflowStep>           // THE WORKFLOW — ordered steps inside this action
  enabled: Boolean
}
```

### WorkflowStep

This is the new concept that replaces the single `ActionType` enum. An action is now a **sequence of steps**, each independently configurable, retryable, and observable.

```
WorkflowStep {
  id, actionId, name
  stepType: StepType                   // see table below
  executionOrder: Int
  config: JSONB                        // step-specific config (varies by stepType)
  retryPolicy: RetryPolicy
  enabled: Boolean
}

RetryPolicy {
  maxAttempts: Int                     // default 3
  initialDelayMs: Long                 // default 1000
  backoffMultiplier: Double            // default 2.0  (exponential)
  maxDelayMs: Long                     // default 30000
  retryableErrors: List<ErrorClass>    // which errors trigger retry vs. fail-fast
}
```

### WorkflowStep types

| StepType                | What it does                                                | Key config fields                                |
|-------------------------|-------------------------------------------------------------|--------------------------------------------------|
| `PARSE_FILE`            | Parse an arrived file using FileParser + FileSpec           | `fileSpecId`, `integrationId`, `filePattern`     |
| `VALIDATE`              | Run ValidationEngine on the current record stream           | `failFast`, `maxErrors`                          |
| `CORRECT`               | Run CorrectionEngine on the current record stream           | `correctionRules` override (or use spec's)       |
| `MERGE_SOURCES`         | Merge window events + parsed file records into one stream   | `mergeKey`, `deduplicateAfterMerge`              |
| `MAP_TO_NOTIFICATION`   | Convert FileRecord → notification payload (template-driven) | `template`, `notificationSchema`                 |
| `TRANSFORM_RECORD`      | Remap fields from one schema to another                     | `fieldMappings`                                  |
| `DEDUPLICATE`           | Remove duplicates using DeduplicationService                | `keyFields`, `scope: WINDOW \| GLOBAL`           |
| `NOTIFY`                | Send notification over a channel                            | `integrationId`, `protocol`, `batchSize`         |
| `GENERATE_FILE`         | Produce an output file using FileGenerator + FileSpec       | `fileSpecId`, `outputFormat`, `fileNameTemplate` |
| `DELIVER_FILE`          | Upload generated file via IntegrationChannel                | `integrationId`, `remotePathTemplate`            |
| `ARCHIVE`               | Save window events/records to S3 as JSONL.gz                | `integrationId`, `pathTemplate`                  |
| `VALIDATE_COMPLETENESS` | Assert expected record count (query DB or external)         | `expectedCountQuery`, `tolerance`                |
| `INVOKE_EXTERNAL`       | Call REST endpoint with retry and templated body            | `url`, `method`, `bodyTemplate`, `headers`       |

### WorkflowExecution (runtime state)

This is what makes the engine **resumable and observable**. Every workflow execution is a first-class entity in the DB.

```
WorkflowExecution {
  id (UUID)
  actionId
  windowInstanceId
  profileId
  status: PENDING | RUNNING | RETRYING | PAUSED | AWAITING_TRIGGER | COMPLETED | FAILED | CANCELLED
  currentStepIndex: Int
  checkpointData: JSONB              // output of last completed step, passed as input to next
  totalAttempts: Int
  lastError: String?
  startedAt, lastUpdatedAt, completedAt
}

WorkflowStepExecution {
  id (UUID)
  workflowExecutionId
  stepId
  stepIndex: Int
  status: PENDING | RUNNING | COMPLETED | FAILED | SKIPPED | RETRYING
  attempt: Int
  inputChecksum: String              // SHA-256 of input data (idempotency key)
  outputData: JSONB                  // serialised output, fed as input to next step
  errorMessage: String?
  startedAt, completedAt
}
```

### WindowInstance (runtime state of an open window)

```
WindowInstance {
  id, profileId, profileVersion
  status: PENDING | OPEN | CLOSING | CLOSED | ERROR | FORCE_CLOSED
  eventCount: Int
  openedAt, closedAt
  openTriggerFiredAt: Instant?       // when the open trigger fired
  closeTriggerState: JSONB           // for compound triggers: tracks which sub-triggers have fired
  arrivedFileHandles: List<FileHandle>  // files that arrived during this window
  workflowExecutions: List<WorkflowExecution>  // all actions that ran
}
```

---

## Implementation Phases

### Phase 0 — Foundation
**Goal:** Replace in-memory stores with PostgreSQL persistence. Every other phase depends on this.

**Tasks:**
- [ ] Move domain entities to `platform-common`: `FileSpecEntity`, `IntegrationEntity`, `CredentialEntity` with JSONB columns for nested config *(entities live in platform-api persistence layer — migration to platform-common pending)*
- [ ] Add Flyway to `platform-api`: `V1__create_filespecs.sql`, `V2__create_integrations.sql`, `V3__create_credentials.sql` *(schema managed via single `db/schema.sql` mounted into PostgreSQL — Flyway migration pending)*
- [x] Wire `application.yml` datasource config
- [x] Replace `HashMap` in `SpecService` with `FileSpecRepository` (JPA)
- [x] Fix API path prefix — align `/api/v1/` → `/api/` to match OpenAPI spec
- [ ] Add `CredentialService` — AES-256-GCM, `POST/DELETE/PUT /api/credentials` *(AES-256-GCM encryption exists in `IntegrationEncryptionService`; standalone CredentialService CRUD endpoints pending)*
- [ ] Remove `platform-pipeline` module

**Deliverable:** Spring Boot app persists FileSpecs and credentials to PostgreSQL. All tests pass.

**Estimated effort:** ~3 hours

---

### Phase 0.5 — Observability Foundation ✅ COMPLETE
Full three-pillar observability stack is running (done in previous session). OTel Collector, Jaeger, Prometheus, Grafana, Elasticsearch, Kibana all wired. Structured JSON logs, traces, and metrics are live.

**Remaining:** HTTP Latency p50/p95/p99 panels need app restart to pick up histogram bucket config added to `application.yml`.

---

### Phase 1a — Domain Entities
**Goal:** Persist the full Profile/Window/Action/Workflow domain to PostgreSQL.

**JPA entities in `platform-common`:**
- [x] `ProfileEntity` — id, name, clientId, status, version, windowConfig (JSONB), tags *(`ProfileJpaEntity` in platform-api persistence layer)*
- [x] `ActionEntity` — owned by Profile, condition, executionOrder, continueOnFailure, steps (JSONB), enabled *(stored as JSONB array on ProfileJpaEntity per design decision below)*
- [x] `WindowInstanceEntity` — status state machine, eventCount, closeTriggerState (JSONB), arrivedFileHandles (JSONB), openedAt, closedAt *(`WindowJpaEntity`)*
- [x] `WindowEventEntity` — individual events per instance, checksum (SHA-256), payload (JSONB) *(`WindowDataJpaEntity`)*
- [x] `WorkflowExecutionEntity` — full execution record with status, currentStepIndex, checkpointData (JSONB) *(`WorkflowExecutionJpaEntity`)*
- [x] `WorkflowStepExecutionEntity` — per-step outcome, inputChecksum, outputData (JSONB) *(`WorkflowStepExecutionJpaEntity`)*
- [ ] `ActionResultEntity` — summary of each action run (links to WorkflowExecution)
- [ ] `ProfileRevisionEntity` — full JSON snapshot on every PUT (rollback support)

**Flyway migrations:**
- [ ] `V4__create_profiles.sql` — profiles + actions *(schema managed via `db/schema.sql` — Flyway migration pending)*
- [ ] `V5__create_window_instances.sql` *(covered in schema.sql)*
- [ ] `V6__create_window_events.sql` *(covered in schema.sql)*
- [ ] `V7__create_workflow_executions.sql` *(covered in schema.sql)*
- [ ] `V8__create_workflow_step_executions.sql` *(covered in schema.sql)*
- [ ] `V9__create_profile_revisions.sql` *(pending — ProfileRevisionEntity not yet created)*

**Design decision — actions and steps storage:**
Store `actions` as a JSONB array on `ProfileEntity`. Each action contains its `steps` array inline. Rationale: actions and steps are always queried together with the profile; JSONB avoids N+1 queries and is flexible for schema evolution. PostgreSQL JSONB is indexable and queryable — not a black box.

**Estimated effort:** ~2 hours

---

### Phase 1b — Profile + Window REST API
**Goal:** Full CRUD API for Profiles and Windows.

**`ProfileController`:**
- [x] `GET /api/profiles` — list with clientId/status/tag filters + pagination
- [x] `POST /api/profiles` — create in DRAFT status
- [x] `GET /api/profiles/{id}` — get with actions and steps inline
- [x] `PUT /api/profiles/{id}` — update (creates revision snapshot, bumps version)
- [x] `DELETE /api/profiles/{id}` — soft delete
- [x] `POST /api/profiles/{id}/enable` — DRAFT/DISABLED → ENABLED (triggers scheduler registration)
- [x] `POST /api/profiles/{id}/disable` — ENABLED → DISABLED (triggers scheduler removal)
- [x] `POST /api/profiles/{id}/validate` — validate config without saving
- [x] `POST /api/profiles/{id}/trigger` — manually open a window immediately
- [ ] `GET /api/profiles/{id}/history` — list revision snapshots *(pending ProfileRevisionEntity)*
- [ ] `POST /api/profiles/{id}/rollback?version=N` — restore a previous revision *(pending ProfileRevisionEntity)*

**`WindowController`:**
- [x] `GET /api/windows` — list instances with profileId/status filters
- [x] `GET /api/windows/{id}` — full detail including workflow executions
- [x] `POST /api/windows/{id}/close` — force close an open window
- [x] `POST /api/windows/{id}/reprocess` — re-run action chain on a closed window
- [x] `GET /api/windows/{id}/events` — paginated event list
- [x] `GET /api/windows/{id}/executions` — list all workflow executions for this window *(WorkflowExecutionController)*

**Services:**
- [x] `ProfileService` — CRUD + enable/disable (calls `WindowSchedulingService`)
- [x] `WindowService` — instance lifecycle + event ingestion + manual trigger *(WindowStateService + WindowSchedulingService)*
- [ ] `ProfileRevisionService` — snapshot on update, rollback *(pending ProfileRevisionEntity)*

**Estimated effort:** ~3 hours

---

### Phase 1c — Window Scheduler
**Goal:** Quartz-based scheduler that opens and closes windows on the configured triggers.

**`platform-scheduler` module:**

**Scheduler core:**
- [x] `WindowSchedulingService`
  - `scheduleProfile(profile)` — creates Quartz CronTriggers for open and close events
  - `unscheduleProfile(profileId)` — removes all triggers for this profile
  - `rescheduleProfile(profile)` — atomic unschedule + schedule (called on PUT enable/disable)
  - Called by `ProfileService.enable()` and `ProfileService.disable()`

**Quartz Jobs:**
- [x] `WindowOpenJob` — fires on `openTrigger` (for TIME_BASED triggers)
  - Calls `WindowStateService.open(profileId)` → creates `WindowInstance` (PENDING → OPEN)
  - Persists `openTriggerFiredAt`
  - Initialises `closeTriggerState` for compound triggers
  - Publishes `WindowOpenedEvent` domain event
  - Fires `ON_OPEN` action chain via `WorkflowOrchestrator`
  - Schedules `WindowRecurringJob` if `recurringInterval` is set
- [x] `WindowCloseJob` — fires on `closeTrigger` (for TIME_BASED triggers)
  - Evaluates trigger conditions (may be a compound trigger — not all conditions met yet)
  - If all conditions met: calls `WindowStateService.startClosing()` → OPEN → CLOSING
  - Fires `ON_CLOSING` (or `ON_EMPTY_CLOSE` if eventCount == 0 and `allowEmptyClose == true`)
  - On workflow completion: calls `WindowStateService.close()` → CLOSED
- [ ] `WindowRecurringJob` — fires every `recurringInterval` while window is OPEN *(not yet implemented)*
  - Fires `RECURRING_WHILE_OPEN` action chain
  - If window has already closed: no-op

**Trigger evaluation:**
- [x] `TriggerEvaluator` interface — `evaluate(trigger, windowInstance): TriggerResult`
- [x] `TimeTriggerEvaluator` — checks cron schedule has fired
- [x] `FileArrivalTriggerEvaluator` — checks `windowInstance.arrivedFileHandles` for matching file
- [x] `EventCountTriggerEvaluator` — checks `windowInstance.eventCount >= threshold`
- [x] `CompoundTriggerEvaluator` — delegates to sub-evaluators, applies AND/OR logic
- [ ] `TriggerStateService` — persists compound trigger state per window instance *(partially handled inline in WindowCloseChecker)*

**State machine:**
- [x] `WindowStateService`
  - `open(profileId): WindowInstance`
  - `startClosing(instanceId): WindowInstance`
  - `close(instanceId): WindowInstance`
  - `forceClose(instanceId, reason): WindowInstance`
  - `markError(instanceId, error): WindowInstance`
  - Guards: each transition validates the current state (cannot close an already-closed window)

**Supporting services:**
- [ ] `DeduplicationService` — `isDuplicate(instanceId, payload): Boolean` via SHA-256 checksum on `WindowEventEntity`
- [ ] `EventIngestionService` — receives events (from Kafka consumer / REST inbound), stores as `WindowEventEntity`, checks dedup, evaluates `EVENT_COUNT` trigger
- [x] `FileArrivalService` — called by integration channels when a file is downloaded; stores handle on `WindowInstance`, evaluates `FILE_ARRIVAL` trigger; fires close sequence *(`FileArrivalHandlerService`)*

**Quartz persistence:**
- [ ] Configure Quartz JDBC store in `application.yml` (not in-memory — survives restarts)
- [ ] `V10__quartz_tables.sql` — standard Quartz schema for PostgreSQL

**Estimated effort:** ~4 hours

---

### Phase 1d — Workflow Orchestration Engine
**Goal:** The engine that executes the ordered `WorkflowStep` list inside each action, with full state persistence, retry, and recovery.

This is the platform's reliability core. It must survive crashes, restart from the last completed step, and handle partial failures gracefully.

**`WorkflowOrchestrator`** (in `platform-scheduler`):
- [x] `execute(action, windowInstance): WorkflowExecution` *(`WorkflowOrchestratorImpl` in platform-api)*
  - Creates a `WorkflowExecution` record (PENDING)
  - Iterates through enabled steps in `executionOrder`
  - For each step: creates `WorkflowStepExecution` record, delegates to `StepExecutor`
  - Passes the output of step N as the input context of step N+1 (via `checkpointData`)
  - On step failure: consults `RetryPolicy`, schedules retry or marks FAILED
  - On action failure with `continueOnFailure == false`: marks `WorkflowExecution` FAILED, fires `ON_ERROR` chain
  - On action failure with `continueOnFailure == true`: logs, continues to next step
  - On full completion: marks `WorkflowExecution` COMPLETED

**`StepExecutor` interface:**
```kotlin
interface StepExecutor {
    fun supports(stepType: StepType): Boolean
    suspend fun execute(step: WorkflowStep, context: StepContext): StepResult
}

data class StepContext(
    val windowInstance: WindowInstance,
    val workflowExecution: WorkflowExecution,
    val inputData: Any?,               // output from previous step
    val arrivedFiles: List<FileHandle>,
    val profile: Profile
)

data class StepResult(
    val status: StepStatus,
    val outputData: Any?,             // passed as inputData to next step
    val recordsProcessed: Int,
    val error: String?
)
```

**Step executor implementations** (enough for end-to-end demo):
- [x] `ParseFileStepExecutor` — resolves file from context, calls `TransformationPipeline.parse()`, emits `Flow<ParsedRecord>` as output *(implemented inline in `WorkflowOrchestratorImpl.executeParseFile()`)*
- [x] `ValidateStepExecutor` — runs `ValidationEngine` on input `Flow<ParsedRecord>`, passes validated records as output *(implemented inline in `WorkflowOrchestratorImpl.executeValidate()`)*
- [ ] `CorrectStepExecutor` — runs `CorrectionEngine` on input records, passes corrected records as output *(pending separate class extraction)*
- [ ] `MergeSourcesStepExecutor` — merges `Flow<ParsedRecord>` from file parse with events collected in window
- [ ] `MapToNotificationStepExecutor` — converts `ParsedRecord` to notification payload using a configured template
- [x] `NotifyStepExecutor` — calls `IntegrationChannel.send()` *(implemented inline in `WorkflowOrchestratorImpl.executeNotify()`)*
- [ ] `GenerateFileStepExecutor` — calls `FileGenerator` with input records, produces output file bytes
- [ ] `DeliverFileStepExecutor` — calls `IntegrationChannel.upload()` with the generated file
- [ ] `ArchiveStepExecutor` — serialises window events to JSONL.gz, uploads to S3 *(S3ArchivalService exists in platform-integration but not wired as a step)*

**Recovery on startup:**
- [x] `WorkflowRecoveryService` — `@PostConstruct` bean that queries for `WorkflowExecution` records in RUNNING/RETRYING state at startup, marks them FAILED with recovery instructions
- Note: marks as FAILED (not resume) since in-process step context is lost after crash; use `POST /api/windows/{id}/reprocess` to retry

**Retry service:**
- [ ] `RetryScheduler` — schedules delayed retry for a failed step using Quartz `SimpleTrigger`
- [ ] Exponential backoff: `delay = min(initialDelay * backoffMultiplier^attempt, maxDelay)`
- [ ] Retry fires `WorkflowOrchestrator.resumeFrom(executionId, stepIndex)`

**Idempotency:**
- [ ] Each `WorkflowStepExecution` stores `inputChecksum` (SHA-256 of step input)
- [ ] Before re-executing a step: if a COMPLETED execution with the same `inputChecksum` exists for this window, skip and reuse its output
- [ ] This makes retries and manual reprocessing safe — no double-deliveries

**Estimated effort:** ~4 hours

---

### Phase 2 — Integration Channels
**Goal:** Pluggable `IntegrationChannel` system. New module `platform-integration` (partially exists — wire into the new model).

**`IntegrationChannel` interface** (in `platform-common`):
```kotlin
interface IntegrationChannel {
    fun supports(type: IntegrationType): Boolean
    suspend fun testConnectivity(config: IntegrationConfig): ConnectivityResult
}

interface InboundChannel : IntegrationChannel {
    fun poll(config: IntegrationConfig): Flow<FileHandle>
}

interface OutboundChannel : IntegrationChannel {
    suspend fun deliver(config: IntegrationConfig, file: FileContent): DeliveryResult
}

interface EventChannel : IntegrationChannel {
    fun subscribe(config: IntegrationConfig): Flow<RawEvent>
    suspend fun publish(config: IntegrationConfig, events: List<RawEvent>): PublishResult
}
```

**Channel implementations:**
- [ ] `SftpChannel` — inbound poll + outbound upload + connectivity test (refactor existing Camel route into this interface)
- [ ] `S3Channel` — inbound list/download + outbound upload with path templating (refactor existing S3ArchivalService)
- [ ] `KafkaChannel` — subscribe (collect events into window) + publish (deliver records to topic)
- [ ] `DatabaseChannel` (Spring JDBC) — inbound: poll query; outbound: batch insert
- [ ] `RestApiChannel` (Spring WebClient) — inbound: GET with pagination; outbound: POST webhook

**Integration with the window lifecycle:**
- [ ] `IntegrationChannelRegistry` — Spring component scan discovers all `@Component` channel implementations
- [ ] `IntegrationPollingService` — for profiles with `FILE_ARRIVAL` trigger: starts background poller using the configured `InboundChannel`; calls `FileArrivalService` on each discovered file
- [ ] `EventSubscriptionService` — for profiles with Kafka event sources: subscribes to configured topics; calls `EventIngestionService` on each message

**REST API:**
- [ ] `IntegrationController` — CRUD + `POST /api/integrations/{id}/test` (connectivity test)

**Estimated effort:** ~3 hours

---

### Phase 3 — Events → File Pipeline
**Goal:** Complete the outbound direction. `GENERATE_FILE` and `DELIVER_FILE` steps produce real files.

**`FileGenerator` interface** (mirrors `FileParser` in `platform-core`):
```kotlin
interface FileGenerator {
    fun supports(format: FileFormat): Boolean
    suspend fun generate(records: Flow<FileRecord>, config: OutboundConfig, output: OutputStream)
}
```

**Implementations:**
- [ ] `FileGeneratorRegistry` — same `@Component` auto-discovery as `ParserRegistry`
- [ ] `CsvFileGenerator` — generates CSV from `FileRecord` using `OutboundConfig.fieldMappings`
- [ ] `FixedWidthFileGenerator` — generates fixed-width records

**Pipeline:**
- [ ] `EventsToFilePipeline`
  - `EventMapper` — converts raw `WindowEvent.payload` → `FileRecord` using `FieldMapping` config
  - Applies `OutboundConfig.correctionRules` and `validationRules`
  - Streams to `FileGenerator` → to `OutboundChannel.deliver`
- [ ] Wire `GenerateFileStepExecutor` to use `EventsToFilePipeline`
- [ ] Wire `DeliverFileStepExecutor` to call the configured `OutboundChannel`

**End-to-end scenario this unlocks:**
A window collects payment events from Kafka all day → at 17:00 `WindowCloseJob` fires → `ON_CLOSING` action chain runs:
1. `DEDUPLICATE` — remove duplicate events
2. `MERGE_SOURCES` — combine Kafka events with an inbound SFTP file (if hybrid flow)
3. `CORRECT` — apply correction rules
4. `VALIDATE` — validate completeness
5. `GENERATE_FILE` — produce a NACHA ACH file
6. `DELIVER_FILE` — push to Fed's SFTP
7. `ARCHIVE` — save raw events to S3

All driven by a Profile JSON. Zero code changes per client.

**Estimated effort:** ~3 hours

---

### Phase 4 — Remaining Step Executors
- [ ] `ValidateCompletenessStepExecutor` — queries expected record count from DB or external API; fails workflow if mismatch beyond tolerance
- [ ] `TransformAndRouteStepExecutor` — fan-out to multiple channels in parallel (async `coroutineScope { launch { } }`)
- [ ] `InvokeExternalStepExecutor` — templated REST call with retry, timeout, response validation
- [ ] `DedupGlobalStepExecutor` — cross-window deduplication (not just within one window)

---

### Phase 5 — Advanced Features
- [ ] `ON_THRESHOLD_REACHED` trigger — re-armable event count threshold; fires mid-window without closing it
- [ ] `ON_DUPLICATE_DETECTED` condition — fires when `DeduplicationService` rejects an event
- [ ] Profile version diff — compare any two revision snapshots side-by-side via `GET /api/profiles/{id}/history/{v1}/diff/{v2}`
- [ ] NACHA / ISO 20022 `FileGenerator` implementations
- [ ] Built-in FileSpec templates — NACHA-CCD, ISO20022-pain.001, EDI-820 (zero-config client onboarding)
- [ ] `WorkflowStepCondition` — conditional step execution: `if: "context.eventCount > 0"` (Spring Expression Language)

---

## Workflow Execution — Worked Example

To make the orchestration concrete, here is the full execution trace for Pattern 3 (hybrid: events + file):

```
Profile: "ACME-Payments"
  Window: opens 08:00 weekdays, closes when (17:00 AND acme-payments_*.csv arrives)
  ON_OPEN action:
    steps: [ NOTIFY(slack, "window opened") ]
  ON_CLOSING action:
    steps:
      1. PARSE_FILE     { integrationId: "acme-sftp", filePattern: "acme-payments_*.csv", fileSpecId: "acme-csv-spec" }
      2. MERGE_SOURCES  { mergeKey: "transactionId" }
      3. DEDUPLICATE    { keyFields: ["transactionId"], scope: "WINDOW" }
      4. CORRECT        {}   (uses spec's correctionRules)
      5. VALIDATE       { failFast: false, maxErrors: 10 }
      6. GENERATE_FILE  { fileSpecId: "nacha-ach-spec", fileNameTemplate: "ACH_{{date}}_{{windowId}}.ach" }
      7. DELIVER_FILE   { integrationId: "fed-sftp-outbound", remotePathTemplate: "/outbound/{{date}}/" }
      8. ARCHIVE        { integrationId: "s3-archive", pathTemplate: "acme/{{year}}/{{month}}/{{windowId}}/" }
      9. NOTIFY         { integrationId: "acme-webhook", message: "Delivery complete: {{recordsProcessed}} records" }

--- Runtime trace ---
08:00  WindowOpenJob fires
       WindowInstance created: status=OPEN
       ON_OPEN action starts → WorkflowExecution(id=WE-001, status=RUNNING)
         Step 1: NotifyStepExecutor → Slack "window opened" → COMPLETED
       WorkflowExecution WE-001 → COMPLETED

08:00–17:00  EventIngestionService receives 847 Kafka events
             WindowEventEntity rows created, checksums stored
             EVENT_COUNT trigger: threshold not configured, no-op

14:32  IntegrationPollingService picks up "acme-payments_20260321.csv" on SFTP
       FileArrivalService called:
         windowInstance.arrivedFileHandles.add("acme-payments_20260321.csv")
         FILE_ARRIVAL sub-trigger → FIRED
         CompoundTriggerEvaluator: TIME_BASED not yet fired → compound not complete
         No close yet.

17:00  WindowCloseJob fires (TIME_BASED trigger)
       TriggerStateService: TIME_BASED sub-trigger → FIRED
       CompoundTriggerEvaluator: both FILE_ARRIVAL ✓ and TIME_BASED ✓ → COMPOUND_AND complete
       WindowStateService.startClosing(instanceId) → status=CLOSING
       ON_CLOSING action starts → WorkflowExecution(id=WE-002, status=RUNNING, currentStepIndex=0)

       Step 1 — PARSE_FILE
         ParseFileStepExecutor resolves "acme-payments_20260321.csv" from arrivedFileHandles
         CsvFileParser + "acme-csv-spec" → Flow<ParsedRecord> (1,243 records)
         WorkflowStepExecution(step=1) → COMPLETED
         checkpointData updated: { records: Flow<ParsedRecord>, recordCount: 1243 }

       Step 2 — MERGE_SOURCES
         MergeSourcesStepExecutor merges:
           - 1,243 records from Step 1 output (file)
           - 847 events from windowInstance (Kafka)
         mergeKey="transactionId": 847 events matched and enriched with file data
         396 file-only records kept as-is
         Total: 1,243 merged records
         WorkflowStepExecution(step=2) → COMPLETED

       Step 3 — DEDUPLICATE
         DeduplicationService: 12 duplicates detected and removed
         Output: 1,231 records
         WorkflowStepExecution(step=3) → COMPLETED

       Step 4 — CORRECT (CorrectionEngine)
         DATE_FORMAT_COERCE, TRIM, UPPERCASE applied
         WorkflowStepExecution(step=4) → COMPLETED

       Step 5 — VALIDATE (ValidationEngine)
         3 records fail NOT_NULL on "routingNumber"
         failFast=false: records logged as errors, removed from stream
         WorkflowStepExecution(step=5) → COMPLETED
         Output: 1,228 valid records

       Step 6 — GENERATE_FILE
         NachaFileGenerator produces ACH_20260321_WI-042.ach (1,228 entries)
         WorkflowStepExecution(step=6) → COMPLETED

       Step 7 — DELIVER_FILE
         SftpChannel.deliver to "fed-sftp-outbound" /outbound/20260321/
         WorkflowStepExecution(step=7) → COMPLETED

       Step 8 — ARCHIVE
         S3Channel.upload raw events + validation report as JSONL.gz
         WorkflowStepExecution(step=8) → COMPLETED

       Step 9 — NOTIFY
         RestApiChannel POST to acme-webhook: "Delivery complete: 1228 records"
         WorkflowStepExecution(step=9) → COMPLETED

       WorkflowExecution WE-002 → COMPLETED
       WindowStateService.close(instanceId) → status=CLOSED
```

**If app crashes between steps 6 and 7:**
On restart, `WorkflowRecoveryService` finds WE-002 in RUNNING state with `currentStepIndex=6`.
It resumes from Step 7. `inputChecksum` on Step 7 matches the file generated in Step 6 → idempotency check passes → delivery proceeds safely.

---

## Resilience Design

The workflow engine is built for production reliability from day one:

| Failure scenario | How the platform handles it |
|---|---|
| App crash mid-workflow | `WorkflowRecoveryService` resumes from last completed step on restart |
| Step fails (transient error) | `RetryPolicy` with exponential backoff via Quartz `SimpleTrigger` |
| Step fails (permanent error) | Marked FAILED, `ON_ERROR` action chain fires (can alert/archive/rollback) |
| Duplicate event arrives | `DeduplicationService` checksum check — silent discard |
| Window stays open too long | `maxOpenDuration` safety timeout triggers force-close + `ON_ERROR` chain |
| Re-delivery risk on retry | `inputChecksum` idempotency: skip step if same input already produced a COMPLETED output |
| Compound trigger never completes | `maxOpenDuration` provides a safety backstop |
| Quartz scheduler crash | Quartz JDBC store: jobs survive restart without loss |
| Partial file delivery | `DELIVER_FILE` step is retried — outbound channel implementations must be idempotent |

---

## Key Design Decisions

| Decision | Choice | Reason |
|---|---|---|
| Actions and steps storage | JSONB array on `ProfileEntity` | Always queried together; no N+1; flexible schema evolution |
| Workflow state | `WorkflowExecutionEntity` in PostgreSQL | Durable, queryable, survives restarts |
| Step output passing | `checkpointData` JSONB on `WorkflowExecution` | Enables resume from any step after crash |
| Step idempotency | `inputChecksum` on `WorkflowStepExecution` | Safe retries and reprocessing |
| Retry mechanism | Quartz `SimpleTrigger` with delay | Reuses existing scheduler infrastructure |
| Compound trigger state | `closeTriggerState` JSONB on `WindowInstance` | Persists which sub-triggers have fired |
| Integration channel design | Interface per direction (Inbound/Outbound/Event) | Clean contracts, easy to add new channels |
| Module-as-service readiness | Domain events as integration contract, no cross-module FKs, module-prefixed schema | Monolith today → microservices later without rework |
| `platform-common` scope | Entities + interfaces + DTOs + domain events only. No Spring. | Zero-dependency library any module or service can use |
| Quartz persistence | JDBC store from day 1 | Enables HA clustering later with no config change |
| Auth | JWT (already in build.gradle) | Already declared; implement in Phase 0 |

---

## Domain Events (the microservice contract)

These events live in `platform-common` as plain Kotlin data classes. Today they are published in-process. In a future microservice split, the producer module publishes to Kafka and the consumer module subscribes.

| Event | Published by | Consumed by |
|---|---|---|
| `ProfileEnabledEvent` | `platform-api` | `platform-scheduler` — registers Quartz triggers |
| `ProfileDisabledEvent` | `platform-api` | `platform-scheduler` — removes triggers |
| `ProfileUpdatedEvent` | `platform-api` | `platform-scheduler` — reschedules |
| `WindowOpenedEvent` | `platform-scheduler` | `platform-integration` — starts polling/subscribing |
| `WindowClosingEvent` | `platform-scheduler` | `platform-scheduler` (self) — starts action chain |
| `WindowClosedEvent` | `platform-scheduler` | `platform-integration` — stops polling |
| `FileArrivedEvent` | `platform-integration` | `platform-scheduler` — evaluates FILE_ARRIVAL trigger |
| `RawEventReceivedEvent` | `platform-integration` | `platform-scheduler` — ingests, checks EVENT_COUNT trigger |
| `WorkflowStartedEvent` | `platform-scheduler` | `platform-api` — observability, websocket push |
| `WorkflowStepCompletedEvent` | `platform-scheduler` | `platform-api` — observability |
| `WorkflowCompletedEvent` | `platform-scheduler` | `platform-api` — update window status, alert |
| `WorkflowFailedEvent` | `platform-scheduler` | `platform-api` — alert, fire ON_ERROR chain |

---

## What to Work on Right Now

The natural build order to reach a working end-to-end demo fastest:

```
Phase 0  →  Phase 1a  →  Phase 1b  →  Phase 1c  →  Phase 1d  →  Phase 2  →  Phase 3
 ~3h          ~2h          ~3h          ~4h           ~4h          ~3h          ~3h
```

**Immediate next step:** Phase 0 — PostgreSQL persistence. This is the prerequisite for every phase above it.

**First demo milestone** (end of Phase 1d):
> Create a Profile via `POST /api/profiles`, enable it, and watch windows open/close on schedule with the action workflow executing step-by-step, all state persisted, recoverable after a restart.

**Full end-to-end demo milestone** (end of Phase 3):
> ACME drops a payments CSV on SFTP. Platform collects Kafka events all day. At 17:00 the compound trigger fires. The workflow runs 9 steps — parse, merge, deduplicate, correct, validate, generate NACHA file, deliver to Fed SFTP, archive to S3, notify client webhook. Zero code written per client — entirely driven by the Profile JSON.

---

## Docs Alignment Notes

1. **API path prefix** — docs say `/api/specs`, code uses `/api/v1/specs`. Fix in Phase 0.
2. **FileSpec model** — code `FileSpec` is ground truth; update docs to match once Phase 0 is done.
3. **`platform-pipeline` references** — remove from docs when module is dropped.
4. **`platform-integration` module** — partially exists; bring it fully in line with `IntegrationChannel` interface design in Phase 2.
5. **Status badges** — add ✅ / 🔨 / 📋 badges to each doc page's frontmatter once phases complete.
