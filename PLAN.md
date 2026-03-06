# Transform Platform — Implementation Plan

> **Last updated:** 2026-03-06
> **Current focus:** Phase 0 — PostgreSQL persistence layer (not started)
> **How to use this doc:** Update "Current focus" and tick off tasks as they're completed. Add notes under each phase as decisions are made.

---

## Ground Truth: Where We Stand

### Code that actually exists and works
| Module | Status | What's built |
|---|---|---|
| `platform-core` | ✅ Done | CSV/Fixed-Width/XML parsers, CorrectionEngine, ValidationEngine, TransformationPipeline, KafkaRecordWriter, ParserRegistry, unit tests |
| `platform-api` | ⚠️ Partial | SpecController, TransformController — but using in-memory HashMaps, no DB, API paths are `/api/v1/...` not `/api/...` |
| `platform-common` | ❌ Empty | Only build.gradle.kts |
| `platform-scheduler` | ❌ Empty | Only build.gradle.kts — Quartz dependency declared, no code |
| `platform-pipeline` | ❌ Empty | Spring Batch declared, will be **removed** — not needed with Window/Action model |

### Docs ahead of code (target to implement toward)
- Window, Action, Profile domain — designed, zero code
- Integration channels (SFTP, Kafka, DB, REST, S3) — designed, zero code
- Events → File pipeline — designed, zero code
- PostgreSQL persistence — driver in build.gradle, not wired up
- Credential encryption — designed, zero code
- OpenAPI spec has ~50 endpoints, code has ~7

---

## Module Restructure Decision

Remove `platform-pipeline` (Spring Batch is the wrong model — Window/Action replaces it).
Add `platform-integration` (new module for integration channel implementations).

**Final module layout:**

```
platform-common/        ← shared domain entities (JPA), DTOs, exceptions
platform-core/          ← transformation engine (parsers, correction, validation) ✅ DONE
platform-integration/   ← integration channel implementations (SFTP, Kafka, DB, REST, S3) NEW
platform-scheduler/     ← Window scheduler, Quartz jobs, WindowInstance state machine
platform-api/           ← Spring Boot REST API (all controllers, services)
```

Dependency flow:
```
platform-api
  └── platform-scheduler
        └── platform-integration
              └── platform-common
  └── platform-core
        └── platform-common
```

---

## Implementation Phases

### Phase 0 — Foundation (prerequisite for all phases)
**Goal:** Replace in-memory stores with real PostgreSQL persistence. Everything else depends on this.

**Tasks:**
1. **Move domain entities to `platform-common`**
   - `FileSpecEntity` (JPA) — maps current `FileSpec` data class to a DB table
   - `FieldSpec`, `CorrectionRule`, `ValidationRule` — stored as JSONB column on FileSpecEntity
   - `IntegrationEntity` — base entity for all integration types
   - `CredentialEntity` — stores encrypted credential blobs

2. **Add Flyway to `platform-api`**
   - Migration `V1__create_filespecs.sql`
   - Migration `V2__create_integrations.sql`
   - Migration `V3__create_credentials.sql`

3. **Wire PostgreSQL in `platform-api`**
   - `application.yml` with datasource config
   - `FileSpecRepository extends JpaRepository<FileSpecEntity, String>`
   - Replace `HashMap` in `SpecService` with the JPA repository

4. **Fix API path prefix**
   - Align code paths from `/api/v1/` to `/api/` to match the openapi.yaml spec

5. **Add `CredentialService`**
   - AES-256-GCM encrypt/decrypt
   - `POST /api/credentials`, `DELETE /api/credentials/{ref}`, `PUT /api/credentials/{ref}` (rotate)

**Deliverable:** Running Spring Boot app that persists FileSpecs and credentials to PostgreSQL. All existing tests still pass.

---

### Phase 1 — Core Domain: Profile, Window, Action
**Goal:** Implement the aggregate root that drives batch workflows. This is the platform's unique value proposition.

#### Phase 1a — Domain Entities
1. **`platform-common`: JPA entities**
   - `ProfileEntity` — stores id, name, clientId, status, version, window config (JSONB), tags
   - `ActionEntity` — owned by Profile, stores condition, type, executionOrder, config (JSONB), enabled
   - `WindowInstanceEntity` — runtime state (PENDING/OPEN/CLOSING/CLOSED/ERROR), eventCount, openedAt, closedAt
   - `WindowEventEntity` — individual events collected in a window instance, with checksum for dedup
   - `ActionResultEntity` — outcome of each action execution (status, recordsProcessed, error)
   - `ProfileRevisionEntity` — full JSON snapshot on every PUT (version history)

2. **Flyway migrations**
   - `V4__create_profiles.sql`
   - `V5__create_window_instances.sql`
   - `V6__create_window_events.sql`
   - `V7__create_action_results.sql`
   - `V8__create_profile_revisions.sql`

#### Phase 1b — Profile + Action API
3. **`platform-api`: ProfileController**
   - `GET /api/profiles` — list with clientId/status filters
   - `POST /api/profiles` — create in DRAFT
   - `GET /api/profiles/{id}` — get
   - `PUT /api/profiles/{id}` — update (creates revision snapshot)
   - `DELETE /api/profiles/{id}` — soft delete
   - `POST /api/profiles/{id}/enable` — DRAFT/DISABLED → ENABLED
   - `POST /api/profiles/{id}/disable` — ENABLED → DISABLED
   - `POST /api/profiles/{id}/validate` — validate without saving
   - `POST /api/profiles/{id}/rollback?version=N`
   - `GET /api/profiles/{id}/history`
   - `POST /api/profiles/{id}/trigger`

4. **`platform-api`: ActionController**
   - Actions are sub-resources of Profile: `/api/profiles/{id}/actions`
   - `GET`, `POST`, `PUT /{actionId}`, `DELETE /{actionId}`, enable/disable endpoints

5. **`platform-api`: WindowController**
   - `GET /api/windows` — list instances
   - `GET /api/windows/{instanceId}` — get detail
   - `POST /api/windows/{instanceId}/close` — force close
   - `POST /api/windows/{instanceId}/reprocess`
   - `GET /api/windows/{instanceId}/events`

#### Phase 1c — Window Scheduler (platform-scheduler module)
6. **`WindowSchedulingService`** — manages Quartz job lifecycle
   - `scheduleProfile(profile)` — registers OPEN and CLOSE Quartz CronTriggers
   - `unscheduleProfile(profileId)` — removes all triggers for a profile
   - Hot-reload: called by ProfileService on enable/disable/update

7. **Quartz Jobs**
   - `WindowOpenJob` — fires on startCronExpression
     - Creates new `WindowInstance` (PENDING → OPEN)
     - Fires `ON_OPEN` action chain
     - Registers `RecurringJob` if recurringInterval is set
   - `WindowCloseJob` — fires on endCronExpression
     - Transitions `WindowInstance` (OPEN → CLOSING)
     - Fires `ON_CLOSING` or `ON_EMPTY_CLOSE` chain
     - Transitions to CLOSED when chain completes
   - `WindowRecurringJob` — fires every `recurringInterval` while window is OPEN
     - Fires `RECURRING_WHILE_OPEN` action chain

8. **`WindowStateService`** — state machine for WindowInstance
   - `open(instanceId)` → OPEN
   - `startClosing(instanceId)` → CLOSING
   - `close(instanceId)` → CLOSED
   - `markError(instanceId, reason)` → ERROR

9. **`DeduplicationService`** — checks event checksums
   - `isDuplicate(instanceId, payload): Boolean`
   - Uses `WindowEventEntity.checksum` (SHA-256 of payload)

10. **Quartz persistence** — store jobs in PostgreSQL, not in memory
    - Add Quartz JDBC store config to `application.yml`
    - Flyway migration for Quartz tables (`V9__quartz_tables.sql`)

#### Phase 1d — Action Execution Engine
11. **`ActionExecutor` interface** in `platform-scheduler`
    ```kotlin
    interface ActionExecutor {
        fun supports(type: ActionType): Boolean
        suspend fun execute(action: Action, window: WindowInstance): ActionResult
    }
    ```

12. **`ActionChain`** — executes actions for a condition in `executionOrder`
    - Respects `continueOnFailure`
    - On failure: triggers `ON_ERROR` chain recursively (with cycle guard)
    - Records each `ActionResult` to the DB

13. **Initial ActionExecutor implementations** (enough for end-to-end):
    - `FileToEventsActionExecutor` — wraps existing `TransformationPipeline`
    - `EventsToFileActionExecutor` — wraps the outbound pipeline (Phase 4)
    - `NotifyActionExecutor` — simple webhook/log notification (full channels in Phase 2)

**Deliverable:** A Profile can be created via API, enabled, and windows will open/close on schedule. ON_OPEN and ON_CLOSING actions execute. Window state is fully persisted. This is a runnable demo.

---

### Phase 2 — Integration Channels
**Goal:** Build the pluggable `IntegrationChannel` system so actions can actually talk to real systems.

**New module: `platform-integration`**

14. **`IntegrationChannel` interface + registry**
    - `IntegrationChannelRegistry` — Spring component-scan discovers all `@Component` channel implementations
    - `ConnectivityTestService` — tests a channel without running a pipeline

15. **`SftpChannel`** (JSCH library)
    - Inbound: `pollDirectory(config) → Flow<FileHandle>`
    - Outbound: `uploadFile(config, content)`
    - Connectivity test: list remote directory

16. **`KafkaChannel`** (reuses existing Kafka config from platform-core)
    - Consumer: bind to topic, emit events into a WindowInstance
    - Producer: write records to topic

17. **`DatabaseChannel`** (Spring JDBC)
    - Inbound poll: `pollQuery` → emit rows as events
    - Outbound: `insertStatement` batch writer

18. **`RestApiChannel`** (Spring WebClient)
    - Inbound poll: GET endpoint, pagination strategies
    - Outbound webhook: POST batch of records

19. **`S3Channel`** (AWS SDK v2)
    - Inbound: list bucket prefix, download files
    - Outbound: upload file with configurable path template

20. **`IntegrationController`** in `platform-api`
    - CRUD + connectivity test endpoints from the openapi.yaml spec

**Deliverable:** Real SFTP pickup and delivery works. Kafka consumer can collect events into a window. S3 archive action works.

---

### Phase 3 — Events → File Pipeline
**Goal:** Complete the outbound direction so `EVENTS_TO_FILE` actions produce real files.

21. **`FileGenerator` interface** (mirrors `FileParser`)
    ```kotlin
    interface FileGenerator {
        fun supports(format: FileFormat): Boolean
        suspend fun generate(records: Flow<FileRecord>, config: OutboundConfig, output: OutputStream)
    }
    ```

22. **`FileGeneratorRegistry`** — same auto-discovery pattern as `ParserRegistry`

23. **`CsvFileGenerator`** — generates CSV from `FileRecord` using `OutboundConfig.fieldMappings`

24. **`FixedWidthFileGenerator`** — generates fixed-width files

25. **`EventsToFilePipeline`**
    - `EventMapper` — transforms raw event `Map<String, Any>` → `FileRecord` using `FieldMapping`
    - Applies `OutboundConfig.correctionRules` and `validationRules`
    - Streams to `FileGenerator` → to `IntegrationChannel.upload`

26. **`EventsToFileActionExecutor`** — wires `EventsToFilePipeline` into the action chain

**Deliverable:** A window can collect payment events and generate a NACHA-style CSV file, delivered via SFTP, all driven by a Profile config.

---

### Phase 4 — Remaining Action Executors
27. **`ArchiveActionExecutor`** — uses S3Channel to archive window events as JSONL.gz
28. **`ValidateCompletenessActionExecutor`** — queries DB or external source for expected count
29. **`TransformAndRouteActionExecutor`** — fan-out to multiple integrations in parallel
30. **`InvokeExternalActionExecutor`** — calls REST webhook, with retry and templating

---

### Phase 5 — Advanced Features
31. **Threshold triggers** — `ON_THRESHOLD_REACHED` condition, configurable per WindowConfig
32. **Profile versioning** — enforce immutable revisions, diff between versions
33. **`ON_DUPLICATE_DETECTED`** condition — fires when DeduplicationService rejects an event
34. **NACHA / ISO 20022 FileGenerators** — Phase 2 parsers from README become generators too
35. **Spec templates** — built-in FileSpec templates for NACHA-CCD, ISO20022-pain.001, EDI-820

---

## What to Work on Right Now

The natural sequence to get to a working demo fastest:

```
Phase 0  →  Phase 1a  →  Phase 1b  →  Phase 1c  →  Phase 1d  →  Phase 2 (SFTP first)  →  Phase 3
(~3h)       (~2h)         (~3h)         (~4h)          (~3h)          (~3h)                  (~3h)
```

**Immediate next step:** Phase 0 — add PostgreSQL persistence and fix the API path prefix. This unblocks everything else and takes the least time.

---

## Things to Clarify / Decisions Needed

| Decision | Options | Recommendation |
|---|---|---|
| Action config storage | JSONB column vs. separate tables per type | JSONB — simpler, flexible, queryable in PG |
| Window event storage | PostgreSQL vs. Redis | PostgreSQL for now (simpler ops); Redis later for high-volume |
| Quartz clustering | Single-node vs. clustered JDBC | JDBC store from day 1 (enables HA later) |
| Auth for API | JWT (already in build.gradle) vs. API key | JWT — already declared, implement in Phase 0 |
| platform-pipeline removal | Remove module or repurpose | Remove — Spring Batch is the wrong model for this platform |
| `platform-common` scope | Just entities, or also services? | Just entities, interfaces, DTOs, exceptions |

---

## Docs Alignment Notes

A few things in the docs need small corrections once implementation starts:

1. **API path prefix** — docs say `/api/specs`, code currently uses `/api/v1/specs`. Fix in Phase 0.
2. **FileSpec model** — doc version has more fields than code version. The code `FileSpec` is the truth; update docs to match exactly once Phase 0 is done.
3. **`platform-pipeline` references** — remove from docs once the module is dropped.
4. **Status badges** — add "✅ Implemented / 🔨 In Progress / 📋 Planned" badges to each doc page's frontmatter so readers know what's live.
