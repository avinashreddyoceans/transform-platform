---
title: Roadmap
description: Transform Platform implementation plan — phases, priorities, and current focus
sidebar_position: 2
---

# Implementation Roadmap

> **Current focus:** Phase 0 — PostgreSQL persistence layer
>
> This is the living development plan for Transform Platform. Update task status and notes here as work progresses. The `PLAN.md` at the repo root is the authoritative copy — this page mirrors it for visibility.

---

## Ground Truth: Where We Stand

### Code that actually exists and works

| Module | Status | What's built |
|---|---|---|
| `platform-core` | ✅ Done | CSV / Fixed-Width / XML parsers, CorrectionEngine, ValidationEngine, TransformationPipeline, KafkaRecordWriter, ParserRegistry, unit tests |
| `platform-api` | ⚠️ Partial | SpecController, TransformController — in-memory HashMaps, no DB persistence, paths are `/api/v1/...` |
| `platform-common` | ❌ Empty | build.gradle.kts only |
| `platform-scheduler` | ❌ Empty | Quartz dependency declared, no code |
| `platform-pipeline` | 🗑️ To remove | Spring Batch — wrong model, replaced by Window/Action |

### Docs ahead of code (our implementation target)

These are designed and documented — the code needs to catch up:

- Window, Action, Profile domain — zero code
- Integration channels (SFTP, Kafka, DB, REST, S3) — zero code
- Events → File pipeline — zero code
- PostgreSQL persistence — driver declared, not wired
- Credential encryption — designed, zero code
- ~50 OpenAPI endpoints documented, ~7 implemented

---

## Module Structure (Target)

```
platform-common/        ← shared JPA entities, DTOs, interfaces, exceptions
platform-core/          ← transformation engine (parsers, correction, validation) ✅ DONE
platform-integration/   ← integration channel implementations (SFTP, Kafka, DB, REST, S3) NEW
platform-scheduler/     ← Window scheduler, Quartz jobs, WindowInstance state machine
platform-api/           ← Spring Boot REST API (all controllers + services)
```

Dependency flow:
```
platform-api
  ├── platform-scheduler → platform-integration → platform-common
  └── platform-core → platform-common
```

---

## Phase 0 — Foundation

**Goal:** Replace in-memory stores with PostgreSQL. Everything else depends on this.

- [ ] Move domain entities to `platform-common` — `FileSpecEntity`, `IntegrationEntity`, `CredentialEntity` as JPA entities with JSONB columns for nested config
- [ ] Add Flyway to `platform-api` — migrations `V1__create_filespecs.sql`, `V2__create_integrations.sql`, `V3__create_credentials.sql`
- [ ] Wire `application.yml` datasource config and replace `HashMap` in `SpecService` with `FileSpecRepository`
- [ ] Fix API path prefix — align code from `/api/v1/` → `/api/` to match the OpenAPI spec
- [ ] Add `CredentialService` — AES-256-GCM encrypt/decrypt, `POST/DELETE/PUT /api/credentials`
- [ ] Remove `platform-pipeline` module (Spring Batch is the wrong model)

**Deliverable:** Running Spring Boot app that persists FileSpecs and credentials to PostgreSQL. All existing tests pass.

---

## Phase 0.5 — Observability Foundation

**Goal:** Full three-pillar observability — logs, metrics, distributed traces — wired before Phase 1 so every domain event is instrumented from day one.

**Stack:**

| Signal | Tool | Port |
|---|---|---|
| Traces | Jaeger (via OTel Collector) | `:16686` |
| Metrics | Prometheus → Grafana | `:9090` / `:3000` |
| Logs | Elasticsearch → Kibana | `:9200` / `:5601` |

**Code changes only (no new modules):**

- [ ] `platform-api/build.gradle.kts` — add `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `logstash-logback-encoder`
- [ ] `application.yml` — add OTel OTLP endpoint config + tracing sampling rate
- [ ] `logback-spring.xml` — new: structured JSON logs with `traceId`, `spanId`, `correlationId` in every line
- [ ] `ObservabilityConfig.kt` — Micrometer common tags (service name, env)
- [ ] `CorrelationIdFilter.kt` — inject + propagate `X-Correlation-ID`, set MDC per request
- [ ] `TransformMetrics.kt` — custom business counters/timers for records processed, file duration, window events, action execution

**Docker services to add:**

- [ ] `otel-collector` — central hub; receives OTLP from app, routes to Jaeger + Prometheus + Elasticsearch
- [ ] `elasticsearch` — log storage (single-node, 512 MB heap for local dev)
- [ ] `kibana` — log dashboards
- [ ] `prometheus` — metrics storage; scrapes app + OTel Collector
- [ ] `grafana` — metrics dashboards with auto-provisioned datasources + pre-built dashboard
- [ ] `jaeger` — distributed tracing UI (all-in-one image)

**New config files in `.docker/`:**

- [ ] `otel-collector-config.yaml` — OTLP receivers → batch → Jaeger + Prometheus + ES exporters
- [ ] `prometheus.yml` — scrape configs for app and OTel Collector
- [ ] `grafana/provisioning/datasources.yml` — auto-wire Prometheus + Jaeger
- [ ] `grafana/provisioning/dashboards/transform-platform.json` — JVM + HTTP + business metrics dashboard

**Deliverable:** `docker compose up` starts the full stack. App logs are JSON with trace IDs. Traces appear in Jaeger. Business metrics appear in Grafana. All of this is in place before Phase 1 domain code is written.

---

## Phase 1 — Core Domain: Profile, Window, Action

**Goal:** The aggregate root that drives batch workflows — the platform's unique value.

### Phase 1a — Domain Entities
- [ ] `ProfileEntity` — id, name, clientId, status, version, window config (JSONB), tags
- [ ] `ActionEntity` — owned by Profile, condition, type, executionOrder, config (JSONB), enabled
- [ ] `WindowInstanceEntity` — runtime state machine (PENDING/OPEN/CLOSING/CLOSED/ERROR), eventCount, openedAt, closedAt
- [ ] `WindowEventEntity` — individual events collected per window instance, checksum for dedup
- [ ] `ActionResultEntity` — outcome of each action execution (status, recordsProcessed, error)
- [ ] `ProfileRevisionEntity` — full JSON snapshot on every PUT (version history / rollback)
- [ ] Flyway migrations V4–V8 for all new tables

### Phase 1b — Profile + Action REST API
- [ ] `ProfileController` — full CRUD + enable / disable / validate / rollback / history / trigger
- [ ] `ActionController` — sub-resource of Profile: add / update / delete / enable / disable
- [ ] `WindowController` — list instances / get / force-close / reprocess / list events
- [ ] `ProfileService`, `ActionService`, `WindowService` backed by JPA repositories

### Phase 1c — Window Scheduler
- [ ] `WindowSchedulingService` — registers/removes Quartz `CronTrigger`s on enable/disable/update
- [ ] `WindowOpenJob` — fires on `startCronExpression`, creates `WindowInstance` (PENDING → OPEN), fires `ON_OPEN` chain
- [ ] `WindowCloseJob` — fires on `endCronExpression`, transitions OPEN → CLOSING → CLOSED, fires `ON_CLOSING` / `ON_EMPTY_CLOSE`
- [ ] `WindowRecurringJob` — fires every `recurringInterval` while OPEN, runs `RECURRING_WHILE_OPEN` chain
- [ ] `WindowStateService` — state machine (`open`, `startClosing`, `close`, `markError`)
- [ ] `DeduplicationService` — SHA-256 checksum check against `WindowEventEntity`
- [ ] Quartz JDBC persistence — store jobs in PostgreSQL (migration V9 for Quartz tables)

### Phase 1d — Action Execution Engine
- [ ] `ActionExecutor` interface — `supports(type): Boolean` + `execute(action, window): ActionResult`
- [ ] `ActionChain` — ordered execution by `executionOrder`, respects `continueOnFailure`, triggers `ON_ERROR` on failure
- [ ] `FileToEventsActionExecutor` — wraps existing `TransformationPipeline`
- [ ] `EventsToFileActionExecutor` — stub (full wiring in Phase 3)
- [ ] `NotifyActionExecutor` — simple webhook / log notification (full channels in Phase 2)

**Deliverable:** A Profile can be created via API, enabled, and windows open/close on schedule with actions executing and state fully persisted. Runnable end-to-end demo.

---

## Phase 2 — Integration Channels

**Goal:** Pluggable `IntegrationChannel` system — new module `platform-integration`.

- [ ] `IntegrationChannel` interface + `IntegrationChannelRegistry` (Spring `@Component` auto-discovery)
- [ ] `ConnectivityTestService` — tests any channel without running a pipeline
- [ ] `SftpChannel` (JSCH) — inbound poll + outbound upload + connectivity test
- [ ] `KafkaChannel` — consumer (collect events into window) + producer (write records to topic)
- [ ] `DatabaseChannel` (Spring JDBC) — poll query + batch insert/upsert
- [ ] `RestApiChannel` (Spring WebClient) — GET poll with pagination + POST webhook fan-out
- [ ] `S3Channel` (AWS SDK v2) — inbound list/download + outbound upload with path templating
- [ ] `IntegrationController` in `platform-api` — full CRUD + connectivity test endpoints

**Deliverable:** Real SFTP pickup and delivery works. Kafka consumer collects events into a window. S3 archive action works.

---

## Phase 3 — Events → File Pipeline

**Goal:** Complete the outbound direction so `EVENTS_TO_FILE` actions produce real files.

- [ ] `FileGenerator` interface — mirrors `FileParser`: `supports(format)` + `generate(records, config, output)`
- [ ] `FileGeneratorRegistry` — same `@Component` auto-discovery as `ParserRegistry`
- [ ] `CsvFileGenerator` — generates CSV from `FileRecord` using `OutboundConfig.fieldMappings`
- [ ] `FixedWidthFileGenerator` — generates fixed-width files
- [ ] `EventsToFilePipeline` — `EventMapper` (event → `FileRecord`) + correction + validation + `FileGenerator`
- [ ] Wire `EventsToFileActionExecutor` to use `EventsToFilePipeline` + `IntegrationChannel.upload`

**Deliverable:** A window collects payment events and generates a file delivered via SFTP — all driven by a Profile config with zero code changes.

---

## Phase 4 — Remaining Action Executors

- [ ] `ArchiveActionExecutor` — S3Channel archive of window events as JSONL.gz
- [ ] `ValidateCompletenessActionExecutor` — query DB or external source for expected count
- [ ] `TransformAndRouteActionExecutor` — parallel fan-out to multiple integration channels
- [ ] `InvokeExternalActionExecutor` — REST webhook call with retry, timeout, and Handlebars body templating

---

## Phase 5 — Advanced Features

- [ ] `ON_THRESHOLD_REACHED` condition — configurable event count trigger, re-armable
- [ ] Profile versioning diff — compare any two revision snapshots side-by-side
- [ ] `ON_DUPLICATE_DETECTED` condition — fires when `DeduplicationService` rejects an event
- [ ] NACHA / ISO 20022 `FileGenerator` implementations
- [ ] Built-in FileSpec templates — NACHA-CCD, ISO20022-pain.001, EDI-820 (zero-config onboarding)

---

## Key Design Decisions (Locked)

| Decision | Choice | Reason |
|---|---|---|
| Action config storage | JSONB column | Flexible, queryable in PostgreSQL, no schema migration per new action type |
| Window event storage | PostgreSQL | Simpler ops; Redis later if high-volume |
| Quartz persistence | JDBC store from day 1 | Enables HA and clustering later without rework |
| Auth | JWT (already declared) | Already in `build.gradle.kts`, implement in Phase 0 |
| `platform-pipeline` | Remove | Spring Batch is the wrong model; Window/Action replaces it |
| `platform-common` scope | Entities + interfaces + DTOs + exceptions only | No services — keeps the module dependency graph clean |

---

## Fastest Path to a Demo

```
Phase 0 → Phase 1a → Phase 1b → Phase 1c → Phase 1d → Phase 2 (SFTP) → Phase 3
 ~3h         ~2h        ~3h        ~4h         ~3h          ~3h             ~3h
```

One concrete scenario that proves the platform end-to-end:

> ACME Corp drops a payments CSV on their SFTP server at 08:00 every weekday.
> A Profile collects the events all day, then at 17:00 generates a NACHA ACH file
> and delivers it to the Fed's SFTP — driven entirely by JSON config, no code.
