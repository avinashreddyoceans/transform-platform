# Transform Platform — Developer Runbook

Practical reference for working on this codebase day-to-day.

> **Maintenance rule**: Every time a code change is made, update the relevant sections
> in this file, `AGENTS.md`, and `.docker/env.example` / `.run/` configs as appropriate.
> See `AGENTS.md` §9 for the full documentation maintenance table.

---

## Table of Contents

1. [Local Setup](#1-local-setup)
2. [Running the Application](#2-running-the-application)
3. [Running Tests](#3-running-tests)
4. [Common Gradle Tasks](#4-common-gradle-tasks)
5. [How the Pipeline Works](#5-how-the-pipeline-works)
6. [Adding a New Parser](#6-adding-a-new-parser)
7. [Adding a New Writer](#7-adding-a-new-writer)
8. [Working with Specs via the API](#8-working-with-specs-via-the-api)
9. [Kafka Topics and Event Schema](#9-kafka-topics-and-event-schema)
10. [Database Migrations](#10-database-migrations)
11. [Module Dependency Map](#11-module-dependency-map)
12. [Troubleshooting](#12-troubleshooting)

---

## 1. Local Setup

### Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 21+ | `java -version` to verify |
| Docker Desktop | Latest | Required for Postgres + Kafka |
| IntelliJ IDEA | 2023.3+ | Run config included — see below |

### Start infrastructure

```bash
docker compose -f .docker/docker-compose.yml up -d
```

This starts: **PostgreSQL 16** (`:5432`), **Kafka** (`:9092`), **Kafka UI** (`:8090`).

Verify everything is healthy:

```bash
docker compose -f .docker/docker-compose.yml ps
```

### IntelliJ run configuration

A ready-to-use run config is at `.idea/runConfigurations/TransformPlatformApi_Local.xml`.
It is picked up automatically when you open the project. All environment variables are pre-filled for local use.

Select **TransformPlatformApi - Local** in the Run/Debug dropdown and press Run.

### Manual environment variables

If running from the terminal, all defaults are baked into `application.yml` so no `.env` file
is required out of the box. To override, copy `.docker/env.example` and export:

```bash
export $(cat .docker/env.example | grep -v '^#' | xargs)
./gradlew :platform-api:bootRun
```

---

## 2. Running the Application

### Via Gradle

```bash
./gradlew :platform-api:bootRun
```

### Via IntelliJ

Run the **TransformPlatformApi - Local** configuration. Hot-reload works with
Spring DevTools if added to the classpath.

### Verify startup

```
GET http://localhost:8080/actuator/health
```

Swagger UI: `http://localhost:8080/swagger-ui`

API docs (JSON): `http://localhost:8080/api-docs`

---

## 3. Running Tests

```bash
# All tests
./gradlew test

# Single module
./gradlew :platform-core:test

# Single test class
./gradlew :platform-core:test --tests "com.transformplatform.core.CsvFileParserTest"

# Single test case (Kotest)
./gradlew :platform-core:test --tests "com.transformplatform.core.CsvFileParserTest*quoted fields*"

# With output shown
./gradlew :platform-core:test --info
```

Test reports are at `platform-core/build/reports/tests/test/index.html`.

### Test framework

All tests use **Kotest**. Three spec styles are used:

| Style | Used for | Example |
|-------|---------|---------|
| `ShouldSpec` | Transformation rules | `CorrectionEngineTest` |
| `DescribeSpec` | Parser behaviour | `CsvFileParserTest` |
| `BehaviorSpec` | Business rules (Given/When/Then) | `ValidationEngineTest` |
| `FunSpec` | Simple function tests | `ParserRegistryTest` |

---

## 4. Common Gradle Tasks

```bash
# Compile only
./gradlew :platform-core:compileKotlin

# Build all (skip tests)
./gradlew build -x test

# Build and test everything
./gradlew build

# Clean build
./gradlew clean build

# Check for dependency updates (if plugin installed)
./gradlew dependencyUpdates

# See all tasks for a module
./gradlew :platform-api:tasks
```

---

## 5. How the Pipeline Works

```
InputStream
    │
    ▼
[ParserRegistry]  ──resolves──▶  [FileParser implementation]
    │                              (CSV / FixedWidth / XML)
    │  Flow<ParsedRecord>
    ▼
[CorrectionEngine]  ──applies correction rules──▶  ParsedRecord (fields mutated)
    │
    ▼
[ValidationEngine]  ──runs validation rules──▶  ParsedRecord (errors attached)
    │
    ▼
[Router / Filter]
    ├── FATAL errors  ──▶  skip + log
    ├── skipInvalidRecords=true + errors  ──▶  skip + count
    └── valid / warning  ──▶  pass through
    │
    ▼
[RecordWriter]  ──▶  Kafka / File / Webhook / DB
    │
    ▼
ProcessingResult  (totals, status, error list)
```

Key facts:
- The pipeline is **stream-based** (`kotlinx.coroutines.flow.Flow`) — files are never loaded fully into memory.
- Errors are **collected per record**, not thrown. A record with errors is still passed to the writer unless it has a `FATAL` severity error or `skipInvalidRecords = true`.
- `CorrectionEngine` runs **before** `ValidationEngine` so corrections clean data before rules run.

---

## 6. Adding a New Parser

1. Create a class in `platform-core/.../parsers/impl/` implementing `FileParser`.
2. Annotate it `@Component`.
3. Implement `supports(format)`, `parse(input, spec)`, and optionally `validateSpec(spec)`.

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

That is all. `ParserRegistry` auto-discovers it via Spring DI. No other changes needed.

---

## 7. Adding a New Writer

1. Create a class in `platform-core/.../writers/` implementing `RecordWriter`.
2. Annotate it `@Component`.
3. Implement `supports(destinationType)`, `write(record, request)`, and optionally `flush(request)`.

```kotlin
@Component
class S3RecordWriter(...) : RecordWriter {

    override val writerName = "S3_WRITER"

    override fun supports(type: DestinationType) = type == DestinationType.OUTPUT_FILE

    override suspend fun write(record: ParsedRecord, request: PipelineRequest) {
        // write record bytes to S3
    }
}
```

Add the new `DestinationType` value to the enum in `TransformationPipeline.kt` if needed.

---

## 8. Working with Specs via the API

### Create a spec

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
      { "ruleId": "trim-account", "field": "accountNumber", "correctionType": "TRIM",      "applyOrder": 1 },
      { "ruleId": "coerce-date",  "field": "txDate",        "correctionType": "DATE_FORMAT_COERCE", "value": "yyyy-MM-dd", "applyOrder": 2 }
    ],
    "validationRules": [
      { "ruleId": "amount-positive", "field": "amount", "ruleType": "MIN_VALUE", "value": "0",
        "message": "Amount must be non-negative", "severity": "ERROR" }
    ]
  }' | jq .
```

### Transform a file

```bash
curl -X POST http://localhost:8080/api/v1/transform/file-to-events \
  -F "file=@transactions.csv" \
  -F "specId=<id-from-create-spec>" \
  -F "kafkaTopic=bank-transactions" \
  -F "skipInvalidRecords=false"
```

### List / get / delete specs

```bash
curl http://localhost:8080/api/v1/specs
curl http://localhost:8080/api/v1/specs/<id>
curl -X DELETE http://localhost:8080/api/v1/specs/<id>
```

---

## 9. Kafka Topics and Event Schema

### Topics (auto-created on first publish)

| Topic | Producer | Consumer |
|-------|----------|---------|
| `bank-transactions` | platform-api | downstream consumers |
| Any name passed as `kafkaTopic` | platform-api | — |

### TransformEvent schema

```json
{
  "correlationId": "uuid",
  "specId": "uuid",
  "sequenceNumber": 0,
  "fileName": "transactions.csv",
  "fields": {
    "accountNumber": "***",
    "amount": 1234.56,
    "txDate": "2024-03-15"
  },
  "corrected": false,
  "metadata": {},
  "eventTimestamp": 1710504000000
}
```

### Browse Kafka UI

Open `http://localhost:8090` — shows all topics, consumer groups, and messages.

---

## 10. Database Migrations

Migrations live in `platform-api/src/main/resources/db/migration/` and are run automatically by Flyway on startup.

Naming convention: `V{version}__{description}.sql`

```
V1__create_specs_table.sql
V2__add_correction_rules.sql
```

To create a new migration, add a new file with the next version number. Flyway will apply it on the next startup.

> **Local tip:** Set `ENCRYPTION_ENABLED=false` and `spring.jpa.hibernate.ddl-auto=create-drop` in
> `application-local.yml` (gitignored) to iterate quickly without Flyway strictness.

---

## 11. Module Dependency Map

```
platform-common          (no dependencies)
      │
platform-core ──────────▶ platform-common
      │
platform-pipeline ───────▶ platform-core, platform-common
platform-scheduler ──────▶ platform-core, platform-common
      │
platform-api ────────────▶ platform-core, platform-common, platform-scheduler
```

`platform-api` is the only runnable Spring Boot application (produces a bootJar).
All other modules produce plain JARs consumed by `platform-api`.

---

## 12. Troubleshooting

### Application fails to start: `Spec not found` / Flyway error

Flyway runs `validate` by default. If the DB schema does not match the entities, startup fails.
Either apply the missing migration or set `spring.jpa.hibernate.ddl-auto=update` in a local override.

### Kafka: `LEADER_NOT_AVAILABLE` on first publish

Kafka is auto-creating the topic on first use — this warning is normal and resolves in < 1 second.
Retry logic in the producer handles it transparently.

### Port already in use (5432 / 9092 / 8080)

```bash
# Find the process
lsof -i :5432
# Kill it or change the port in .docker/docker-compose.yml and the PORT env var
```

### `gradle-wrapper.jar` missing after fresh clone

```bash
curl -L -o gradle/wrapper/gradle-wrapper.jar \
  "https://raw.githubusercontent.com/gradle/gradle/v8.6.0/gradle/wrapper/gradle-wrapper.jar"
```

### Tests pass locally but fail in CI

Check that `kotest-extensions-spring` version matches the Kotest BOM version. The project
uses Kotest `5.8.1` with extension `1.1.3`.

### IntelliJ does not see the run config

Go to **Run → Edit Configurations** and confirm the module `transform-platform.platform-api.main`
is available. If not, re-import the Gradle project (`Gradle` tool window → reload).
