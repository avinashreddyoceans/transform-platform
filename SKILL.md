# Transform Platform — Developer Runbook

Practical reference for working on this codebase day-to-day.

> **Maintenance rule**: Every time a code change is made, update the relevant sections
> in this file, `AGENTS.md`, and `.docker/env.example` / `.idea/runConfigurations/` as appropriate.
> See `AGENTS.md` §9 for the full documentation maintenance table.

---

## Table of Contents

1. [Local Setup](#1-local-setup)
2. [Running the Application](#2-running-the-application)
3. [Running the UI](#3-running-the-ui)
4. [Running Tests](#4-running-tests)
5. [Common Gradle Tasks](#5-common-gradle-tasks)
6. [Module Map](#6-module-map)
7. [How File Transformation Works](#7-how-file-transformation-works)
8. [Windows, Profiles & Workflows](#8-windows-profiles--workflows)
9. [Service Integrations (SFTP / FTP / S3)](#9-service-integrations-sftp--ftp--s3)
10. [The AI Assistant](#10-the-ai-assistant)
11. [Working with the API](#11-working-with-the-api)
12. [Kafka Topics and Event Schema](#12-kafka-topics-and-event-schema)
13. [Database Schema](#13-database-schema)
14. [Observability](#14-observability)
15. [CAMT.053 End-to-End Demo](#15-camt053-end-to-end-demo)
16. [Troubleshooting](#16-troubleshooting)

---

## 1. Local Setup

### Prerequisites

| Tool           | Version | Notes                                                    |
|----------------|---------|----------------------------------------------------------|
| JDK            | 21+     | `java -version` to verify                                |
| Docker Desktop | Latest  | Required for Postgres, Kafka, MinIO (and Testcontainers) |
| Node.js        | 18+     | Only needed to build/run `platform-ui`                   |
| IntelliJ IDEA  | 2023.3+ | Run configs included — see below                         |
| `jq`           | Latest  | Used by `run-camt053-e2e.sh` (`brew install jq`)         |

### Start infrastructure

Infrastructure is composed from three Docker Compose **profiles** so you start only what you need:

| Profile         | Services                                                           | Use when                      |
|-----------------|--------------------------------------------------------------------|-------------------------------|
| `core`          | Postgres, pgAdmin, Kafka, Zookeeper, Kafka UI, MinIO               | Day-to-day development        |
| `observability` | OTel Collector, Jaeger, Prometheus, Grafana, Elasticsearch, Kibana | Debugging traces/metrics/logs |
| `sftp`          | Custom SFTP server (inbox/outbox/sent)                             | CAMT.053 / file-arrival demos |

```bash
# Day-to-day — core only (fast, lean)
docker compose -f .docker/docker-compose.yml --profile core up -d

# Core + SFTP — for the CAMT.053 end-to-end demo
docker compose -f .docker/docker-compose.yml --profile core --profile sftp up -d

# Everything
docker compose -f .docker/docker-compose.yml --profile core --profile observability --profile sftp up -d

# Stop everything and wipe volumes (fresh DB)
docker compose -f .docker/docker-compose.yml --profile core --profile observability --profile sftp down -v
```

Verify health:

```bash
docker compose -f .docker/docker-compose.yml ps
```

### Ports

| Service    | Port                       | Credentials / Notes                                          |
|------------|----------------------------|--------------------------------------------------------------|
| Postgres   | 5432                       | `transform_user` / `transform_pass`, db `transform_platform` |
| pgAdmin    | 5050                       | `admin@transform.local` / `admin` (Postgres pre-registered)  |
| Kafka      | 9092                       | broker                                                       |
| Kafka UI   | 8090                       | topic/message browser                                        |
| MinIO      | 9000 (API), 9001 (console) | `minioadmin` / `minioadmin`                                  |
| SFTP       | 2222                       | `sftpuser` / `sftppass`, remote dir `/upload`                |
| Jaeger UI  | 16686                      | traces                                                       |
| Prometheus | 9090                       | metrics                                                      |
| Grafana    | 3001                       | `admin` / `admin` (3000 is reserved for Docusaurus)          |
| Kibana     | 5601                       | logs                                                         |

### IntelliJ run configurations

Run configs in `.idea/runConfigurations/` are picked up automatically:

| Config                                      | Purpose                               |
|---------------------------------------------|---------------------------------------|
| `TransformPlatformApi - Local`              | Run the Spring Boot app locally       |
| `Docker - Core (Postgres + Kafka + MinIO)`  | Start the `core` infra profile        |
| `Docker - Full Stack`                       | Start all infra profiles              |
| `Docker - Stop` / `Docker - Stop and Reset` | Stop infra (Reset also wipes volumes) |
| `All Integration Tests`                     | Run the `integrationTest` source set  |
| `CAMT053 E2E Test`                          | Run `Camt053E2ETest`                  |

### Environment variables

Defaults are baked into `application.yml`, so no `.env` file is required for the `core`
stack. Overridable variables:

| Var                                                        | Default                                               | Purpose                                            |
|------------------------------------------------------------|-------------------------------------------------------|----------------------------------------------------|
| `DB_HOST` / `DB_PORT` / `DB_NAME`                          | `localhost` / `5432` / `transform_platform`           | Postgres connection                                |
| `DB_USER` / `DB_PASS`                                      | `transform_user` / `transform_pass`                   | Postgres credentials                               |
| `KAFKA_BROKERS`                                            | `localhost:9092`                                      | Kafka bootstrap                                    |
| `PORT`                                                     | `8080`                                                | API HTTP port                                      |
| `MINIO_ENDPOINT` / `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` | `http://localhost:9000` / `minioadmin` / `minioadmin` | MinIO backup store                                 |
| `ANTHROPIC_API_KEY`                                        | _(empty)_                                             | Required to use the AI assistant (§10)             |
| `OTEL_ENABLED`                                             | `true`                                                | Set `false` to run without the observability stack |
| `JWT_SECRET` / `CREDENTIAL_ENCRYPTION_KEY`                 | dev placeholder                                       | **Replace before any real deployment**             |

---

## 2. Running the Application

### Quick restart helper

```bash
./restart-app.sh        # kills any running instance, then bootRun -PskipFrontend
```

### Via Gradle

```bash
# Backend only — skips the frontend build (fastest dev loop)
./gradlew :platform-api:bootRun -PskipFrontend

# Full build including the bundled UI
./gradlew :platform-api:bootRun
```

> `:platform-api:bootRun` runs the `buildFrontend` task first (npm build of `platform-ui`).
> Use `-PskipFrontend` for backend-only cycles — see [§3](#3-running-the-ui).

### Via IntelliJ

Run the **TransformPlatformApi - Local** configuration.

### Verify startup

```
GET http://localhost:8080/actuator/health     → {"status":"UP"}
```

- Swagger UI: `http://localhost:8080/swagger-ui`
- API docs (JSON): `http://localhost:8080/api-docs`
- Bundled UI: `http://localhost:8080/` (served from `static/ui/` once built)

---

## 3. Running the UI

`platform-ui` is a **Vite + React** project. It is intentionally **not** a Gradle
subproject (to avoid Kotlin plugin conflicts) — `platform-api/build.gradle.kts` execs
`npm` to build it.

### Development (hot-reload)

```bash
cd platform-ui
npm install
npm run dev        # serves on :5173, proxies /api → Spring Boot :8080
```

Run the backend separately (`./restart-app.sh` or `bootRun -PskipFrontend`).

### Production / bundled build

```bash
./gradlew :platform-api:bootJar
```

`buildFrontend` runs `npm run build`, outputs to `platform-api/src/main/resources/static/ui/`,
and Spring Boot's `processResources` copies it into the jar. Pass `-PskipFrontend` to skip.

---

## 4. Running Tests

There are two test tiers:

```bash
# ── Unit tests — fast, no containers ──────────────────────────────────────────
./gradlew test
./gradlew :platform-core:test
./gradlew :platform-core:test --tests "com.transformplatform.core.CsvFileParserTest"

# ── Integration / E2E tests — require Docker (Testcontainers) ─────────────────
./gradlew :platform-api:integrationTest

# ── Both (integrationTest depends on test) ────────────────────────────────────
./gradlew :platform-api:check
```

The `integrationTest` source set lives in `platform-api/src/integrationTest/` and runs a
full Spring Boot context against a real PostgreSQL container. Key classes:

| Class | Covers |
|-------|--------|
| `AbstractE2ETest` | Shared Testcontainers + Spring Boot setup |
| `Camt053E2ETest` | Full CAMT.053 ingest → parse → validate → Kafka flow |
| `WindowLifecycleE2ETest` | Window open/close/reprocess state transitions |

Test reports: `<module>/build/reports/tests/<task>/index.html`.

### Unit test framework

`platform-core` uses **Kotest** exclusively:

| Style | Used for | Example |
|-------|---------|---------|
| `ShouldSpec` | Transformation rules | `CorrectionEngineTest` |
| `DescribeSpec` | Parser behaviour | `CsvFileParserTest` |
| `BehaviorSpec` | Business rules (Given/When/Then) | `ValidationEngineTest` |
| `FunSpec` | Simple function tests | `ParserRegistryTest` |

`platform-api` / `platform-integration` use **JUnit 5** with Spring Boot test slices
and **SpringMockK** (`@MockkBean`). `platform-integration` unit tests use in-memory H2.

---

## 5. Common Gradle Tasks

```bash
# Compile a single module
./gradlew :platform-core:compileKotlin

# Build all, skip tests
./gradlew build -x test

# Build and test everything (includes integrationTest via check)
./gradlew build

# Clean build
./gradlew clean build

# Backend-only run (skip frontend npm build)
./gradlew :platform-api:bootRun -PskipFrontend

# See all tasks for a module
./gradlew :platform-api:tasks
```

---

## 6. Module Map

```
platform-common         (no dependencies — shared models)
      │
      ├── platform-core ─────────▶ platform-common      (parsers, validators, writers)
      │       │
      │       ├── platform-scheduler ──▶ core, common   (windowing, triggers, Quartz jobs)
      │       └── platform-pipeline ───▶ core, common   (Spring Batch — no source yet)
      │
      └── platform-integration ──▶ platform-common      (Camel SFTP/FTP/S3, MinIO)

platform-api ──▶ platform-common, platform-core, platform-integration, platform-scheduler
```

| Module | Role | Runnable? |
|--------|------|-----------|
| `platform-common` | Shared domain models | No |
| `platform-core` | File parsers, correction/validation engines, record writers | No |
| `platform-integration` | Camel routes (SFTP/FTP/S3 polling), MinIO archival, `ServiceIntegration` model | No |
| `platform-scheduler` | Window lifecycle: trigger evaluators, Quartz jobs, workflow orchestration | No |
| `platform-pipeline` | Spring Batch (no source yet — `bootJar` disabled) | No |
| `platform-api` | REST API + Spring Boot entry point (`TransformPlatformApplicationKt`) | **Yes** |

`platform-api` is the only module that produces a `bootJar`. `platform-ui` is a separate
Vite project bundled into the API jar (see [§3](#3-running-the-ui)).

---

## 7. How File Transformation Works

The synchronous transformation pipeline (`platform-core`) converts a file into events:

```
InputStream
    │
    ▼
[ParserRegistry]  ──resolves──▶  [FileParser]   (CSV / FixedWidth / XML / ISO20022)
    │  Flow<ParsedRecord>
    ▼
[CorrectionEngine]  ──applies correction rules──▶  ParsedRecord (fields mutated)
    │
    ▼
[ValidationEngine]  ──runs validation rules──▶  ParsedRecord (errors attached)
    │
    ▼
[Router / Filter]
    ├── FATAL errors                       ──▶  skip + log
    ├── skipInvalidRecords=true + errors    ──▶  skip + count
    └── valid / warning                     ──▶  pass through
    │
    ▼
[RecordWriter]  ──▶  Kafka / File / Webhook / DB
    │
    ▼
ProcessingResult  (totals, status, error list)
```

Key facts:
- The pipeline is **stream-based** (`kotlinx.coroutines.flow.Flow`) — files are never loaded fully into memory.
- Errors are **collected per record**, not thrown. A record with errors still reaches the writer unless it has a `FATAL` error or `skipInvalidRecords = true`.
- `CorrectionEngine` runs **before** `ValidationEngine` so corrections clean data before rules run.

### Adding a new parser

1. Create a class in `platform-core/.../parsers/impl/` implementing `FileParser`, annotated `@Component`.
2. Implement `supports(format)`, `parse(input, spec)`, and optionally `validateSpec(spec)`.

```kotlin
@Component
class NachaFileParser : FileParser {
    override val parserName = "NACHA_PARSER"
    override fun supports(format: FileFormat) = format == FileFormat.NACHA
    override fun parse(input: InputStream, spec: FileSpec): Flow<ParsedRecord> = flow {
        // stream records, emit each as a ParsedRecord
    }
}
```

`ParserRegistry` auto-discovers it via Spring DI. No other changes needed.

### Adding a new writer

1. Create a class in `platform-core/.../writers/` implementing `RecordWriter`, annotated `@Component`.
2. Implement `supports(destinationType)`, `write(record, request)`, and optionally `flush(request)`.

```kotlin
@Component
class S3RecordWriter(...) : RecordWriter {
    override val writerName = "S3_WRITER"
    override fun supports(type: DestinationType) = type == DestinationType.OUTPUT_FILE
    override suspend fun write(record: ParsedRecord, request: PipelineRequest) { /* ... */ }
}
```

`TransformationPipeline` auto-discovers it. Add a new `DestinationType` enum value if needed.

---

## 8. Windows, Profiles & Workflows

The orchestration layer (`platform-scheduler`) turns a one-shot transformation into a
managed, scheduled workflow. This is the model behind the CAMT.053 demo.

### Concepts

| Concept | What it is |
|---------|-----------|
| **FileSpec** | How to parse a file format (fields, XPath/positions, validation & correction rules) |
| **ServiceIntegration** | A connection to an SFTP/FTP/S3 source or a Kafka sink |
| **Profile** | The complete client workflow config: a `windowConfig` + a list of `actions` |
| **Window** | A bounded processing session created from a Profile; has a lifecycle state |
| **WorkflowExecution** | One run of an action chain; contains ordered step executions |

### Window lifecycle

```
PENDING ──open──▶ OPEN ──close trigger──▶ CLOSING ──actions run──▶ CLOSED / COMPLETED_WITH_ERRORS
```

- **Open triggers**: `TIME_BASED` (cron), `FILE_ARRIVAL`, `MANUAL`.
- **Close triggers**: `TIME_BASED`, `FILE_ARRIVAL`, `EVENT_COUNT`, `SESSION_GAP`, plus compound triggers.
  Trigger evaluators live in `platform-scheduler/.../trigger/`.
- Quartz jobs (`WindowOpenJob`, `WindowCloseJob`, `WindowOrchestratorJob`, `WorkflowMonitorJob`)
  drive transitions; `WindowSchedulingService` registers triggers when a Profile is enabled.

### Actions and steps

An **action** fires on a lifecycle condition (`ON_OPEN`, `ON_FILE_ARRIVED`, `ON_CLOSING`,
`ON_ERROR`) and runs an ordered list of **steps**. Step types:

| Step type | Does |
|-----------|------|
| `PARSE_FILE` | Parse an arrived file with a FileSpec, store records as `window_events` |
| `VALIDATE` | Validate parsed records against the spec's rules |
| `NOTIFY` | Publish to Kafka (or a webhook) |
| `GENERATE_FILE` / `DELIVER_FILE` | Produce / deliver an output file |

Each step takes an optional `retryPolicy` (`maxAttempts`, `initialDelayMs`, `backoffMultiplier`, `maxDelayMs`).

### Typical flow

1. Create a FileSpec, then SFTP + Kafka integrations.
2. Create a Profile referencing them (`windowConfig` + `actions`).
3. `POST /api/profiles/{id}/enable` → registers triggers, creates the first `PENDING` window.
4. Window opens (on schedule or via `POST /api/profiles/{id}/trigger`).
5. A file arrives → close trigger fires → the `ON_FILE_ARRIVED` action chain runs.
6. Inspect results via `/api/windows/{id}` and `/api/executions`.

See [§15](#15-camt053-end-to-end-demo) for a runnable walkthrough.

---

## 9. Service Integrations (SFTP / FTP / S3)

`platform-integration` uses **Apache Camel** to poll remote file sources.

- Routes are **not** auto-discovered (`camel.main.routes-include-pattern: no-op`).
  `DynamicRouteManager` adds routes explicitly at startup and when integrations are enabled.
- Route builders: `SftpFtpRouteBuilder` (FTP/SFTP via `camel-ftp`), `S3PollingRouteBuilder`
  (`camel-aws2-s3`). `FileDownloadProcessor` handles each picked-up file.
- Downloaded files are archived to **MinIO** via `S3ArchivalService` (bucket `transform-downloads`).
- Integration credentials are encrypted at rest (AES-256) by `IntegrationEncryptionService`
  using `transform-platform.security.credential-key`.
- `StartupValidator` re-arms enabled integrations on boot.

Manage integrations through `/api/v1/integrations` — see [§11](#11-working-with-the-api).
Enabling an integration starts its Camel polling route; disabling stops it.

---

## 10. The AI Assistant

A natural-language interface to the platform, powered by Claude.

- **Endpoint**: `POST /api/ai/chat`
- **Requires**: `ANTHROPIC_API_KEY` in the environment. Without it the endpoint is unusable.
- **Config** (`application.yml`, `ai.anthropic.*`): default model `claude-3-5-haiku-20241022`,
  `max-tokens` 4096, override via `AI_MODEL` / `AI_MAX_TOKENS` / `ANTHROPIC_BASE_URL`.
- **How it works**: `AiAssistantService` runs an agentic loop (max 6 iterations) —
  `AnthropicClient` calls Claude with the `PlatformToolSet` tools; Claude can list specs,
  list integrations, query windows/executions, and create profiles. It cannot create
  FileSpecs or Integrations (those need uploads/credentials) and redirects users to the API.

```bash
curl -s -X POST http://localhost:8080/api/ai/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "How many profiles are enabled, and did the last CAMT.053 workflow succeed?"}' | jq .
```

---

## 11. Working with the API

All endpoints are under `http://localhost:8080`. Full schema at `/swagger-ui`.

| Area | Base path | Key operations |
|------|-----------|----------------|
| Specs | `/api/v1/specs` | `POST`, `GET`, `GET/{id}`, `PUT/{id}`, `DELETE/{id}`, `POST/{id}/validate` |
| Transform (one-shot) | `/api/v1/transform` | `POST /file-to-events` (multipart), `POST /schedule`, `GET /status/{correlationId}` |
| Integrations | `/api/v1/integrations` | `GET`, `GET/{id}`, `POST`, `PUT/{id}`, `DELETE/{id}`, `POST/{id}/enable`, `POST/{id}/disable` |
| Profiles | `/api/profiles` | `GET`, `GET/{id}`, `POST`, `PUT/{id}`, `DELETE/{id}`, `POST/{id}/enable`, `/disable`, `/trigger`, `/validate` |
| Windows | `/api/windows` | `GET`, `GET/{id}`, `GET/{id}/data`, `GET/{id}/events`, `POST/{id}/open`, `/close`, `/reprocess` |
| File log | `/api/files` | `GET`, `GET/{id}`, `POST /files/inbound`, `POST /windows/{windowId}/submit-file` (multipart) |
| Executions | `/api/executions` | `GET`, `GET/{id}`, `GET /windows/{windowId}/executions`, `GET /profiles/{profileId}/executions` |
| AI assistant | `/api/ai` | `POST /chat` |

### Create a spec (CSV example)

```bash
curl -s -X POST http://localhost:8080/api/v1/specs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Bank Transactions CSV",
    "format": "CSV",
    "hasHeader": true,
    "delimiter": ",",
    "fields": [
      { "name": "accountNumber", "type": "STRING",  "columnName": "account_number", "sensitive": true },
      { "name": "amount",        "type": "DECIMAL", "columnName": "amount" },
      { "name": "txDate",        "type": "DATE",    "columnName": "date", "format": "yyyy-MM-dd" }
    ],
    "correctionRules": [
      { "ruleId": "trim-account", "field": "accountNumber", "correctionType": "TRIM", "applyOrder": 1 }
    ],
    "validationRules": [
      { "ruleId": "amount-positive", "field": "amount", "ruleType": "MIN_VALUE", "value": "0",
        "message": "Amount must be non-negative", "severity": "ERROR" }
    ]
  }' | jq .
```

### Transform a file (one-shot, synchronous)

```bash
curl -X POST http://localhost:8080/api/v1/transform/file-to-events \
  -F "file=@transactions.csv" \
  -F "specId=<id-from-create-spec>" \
  -F "kafkaTopic=bank-transactions" \
  -F "skipInvalidRecords=false"
```

---

## 12. Kafka Topics and Event Schema

### Topics (auto-created on first publish)

| Topic | Producer | Notes |
|-------|----------|-------|
| Any name passed as `kafkaTopic` / NOTIFY `topic` config | platform-api | Created on demand |
| `bank.statement.entries` | platform-api | Default sink in the CAMT.053 demo |

### TransformEvent schema

```json
{
  "correlationId": "uuid",
  "specId": "uuid",
  "sequenceNumber": 0,
  "fileName": "transactions.csv",
  "fields": { "accountNumber": "***", "amount": 1234.56, "txDate": "2024-03-15" },
  "corrected": false,
  "metadata": {},
  "eventTimestamp": 1710504000000
}
```

Browse messages in Kafka UI at `http://localhost:8090`.

---

## 13. Database Schema

> **There is no Flyway.** The schema is managed by a single file:
> `platform-api/src/main/resources/db/schema.sql`.

- `docker-compose.yml` mounts `schema.sql` into the Postgres container at
  `/docker-entrypoint-initdb.d/`. Postgres runs it **once**, on first start of an empty data volume.
- Hibernate is set to `spring.jpa.hibernate.ddl-auto: none` — it never touches DDL.
- **To change the schema**: edit `schema.sql`, then recreate the database:

  ```bash
  docker compose -f .docker/docker-compose.yml --profile core down -v
  docker compose -f .docker/docker-compose.yml --profile core up -d
  ```

### Tables

| Table | Holds |
|-------|-------|
| `file_specs` | FileSpec definitions (fields, rules) |
| `service_integrations` | SFTP/FTP/S3/Kafka connections (encrypted credentials) |
| `downloaded_files` | Files pulled by Camel integration routes |
| `profiles` | Profile configs (windowConfig + actions) |
| `windows` | Window instances and their lifecycle state |
| `file_log` | Inbound/outbound file arrival log |
| `window_events` | Parsed records collected within a window |
| `window_action_executions` | Workflow (action chain) executions |
| `workflow_step_executions` | Per-step execution records |

---

## 14. Observability

Start the `observability` Docker profile (see [§1](#1-local-setup)). The app emits all
three OTel signals; configuration lives under `otel.*` / `management.otlp.*` in `application.yml`.

| Signal | Path | View at |
|--------|------|---------|
| Traces | OTLP HTTP → OTel Collector :4318 | Jaeger UI `http://localhost:16686` |
| Metrics | Prometheus pull — `/actuator/prometheus` | Grafana `http://localhost:3001` |
| Logs | Log4j2 OTel appender → Collector :4318 | Kibana `http://localhost:5601` |

Notes:
- Logging is **Log4j2**, not Logback (excluded globally in `platform-api/build.gradle.kts`).
  `OtelLoggingConfig` installs the OTel log appender at startup.
- Trace sampling is `1.0` (100%) in dev — reduce in production.
- Running the API **without** the observability stack: set `OTEL_ENABLED=false` to silence
  exporter connection noise.
- Camel routes are instrumented (`camel-micrometer`): `camel_route_policy_seconds_*`,
  `camel_exchange_event_notifier_total`.

---

## 15. CAMT.053 End-to-End Demo

A complete SFTP → parse → validate → Kafka walkthrough using the Window/Profile model.

```bash
# Prereqs: app running + core (and sftp) infra up
docker compose -f .docker/docker-compose.yml --profile core --profile sftp up -d
./restart-app.sh

# Run the demo
./run-camt053-e2e.sh            # real SFTP mode — drops a file on the SFTP server
./run-camt053-e2e.sh --fast     # skip SFTP; submit the file directly via the API
./run-camt053-e2e.sh --teardown # remove all resources the demo created
```

The script creates a FileSpec, SFTP + Kafka integrations, and a Profile with a
`FILE_ARRIVAL → PARSE_FILE → VALIDATE → NOTIFY` action chain, then drives a window through
its full lifecycle and prints the parsed entries and Kafka output.

For the annotated, step-by-step `curl` version see `CAMT053_DEMO.md`.

---

## 16. Troubleshooting

### Application fails to start: schema / entity mismatch

The schema is `schema.sql` only — Hibernate does not create tables. If entities and tables
diverge, recreate the DB (`docker compose ... --profile core down -v && up -d`) after editing
`schema.sql`.

### Kafka: `LEADER_NOT_AVAILABLE` on first publish

Kafka is auto-creating the topic on first use — normal, resolves in < 1 second. Producer
retry logic handles it transparently.

### Port already in use (5432 / 9092 / 8080 / 9000 / 2222)

```bash
lsof -i :5432     # find the process; kill it or change the port mapping
```

### OTel exporter connection errors in the logs

The observability stack isn't running. Either start the `observability` profile or set
`OTEL_ENABLED=false` in your run config.

### Frontend build fails / is slow during `bootRun`

Use `-PskipFrontend` (or `./restart-app.sh`) for backend-only work. To run the UI
separately, `cd platform-ui && npm run dev`.

### Integration tests fail to start containers

`integrationTest` needs a running Docker daemon (Testcontainers). Confirm `docker ps` works.

### `gradle-wrapper.jar` missing after a fresh clone

```bash
curl -L -o gradle/wrapper/gradle-wrapper.jar \
  "https://raw.githubusercontent.com/gradle/gradle/v8.6.0/gradle/wrapper/gradle-wrapper.jar"
```

### Camel route does not pick up an SFTP file

Confirm the integration is **enabled** (`POST /api/v1/integrations/{id}/enable`) and the
SFTP container is healthy (`docker inspect transform-sftp --format '{{.State.Health.Status}}'`).
Watch route activity with `docker logs -f transform-sftp`.

### IntelliJ does not see the run config

**Run → Edit Configurations** and confirm module `transform-platform.platform-api.main`
exists. If not, reload the Gradle project (`Gradle` tool window → reload).
