# Transform Platform — Agent & Contributor Guidelines

This file governs how AI agents and human contributors work on this codebase.
**It must be kept up to date whenever the architecture, conventions, or extension points change.**

---

## Table of Contents

1. [Project Purpose](#1-project-purpose)
2. [Repository Layout](#2-repository-layout)
3. [Technology Stack](#3-technology-stack)
4. [Core Design Principles](#4-core-design-principles)
5. [Architecture — How the Pipeline Works](#5-architecture--how-the-pipeline-works)
6. [Extension Points](#6-extension-points)
7. [Code Conventions](#7-code-conventions)
8. [Testing Requirements](#8-testing-requirements)
9. [Documentation Maintenance Rule](#9-documentation-maintenance-rule)
10. [Pull Request Checklist](#10-pull-request-checklist)
11. [What NOT to Do](#11-what-not-to-do)

---

## 1. Project Purpose

Transform Platform is a spec-driven file transformation engine.
A single `FileSpec` JSON object describes a file format completely — field names, types,
positions, validation rules, and correction rules. The platform uses that spec to:

- **Parse** any file format (CSV, Fixed-Width, XML, …) into a universal `ParsedRecord` stream
- **Correct** dirty data automatically (trim, pad, coerce dates, regex replace, …)
- **Validate** every record against business rules
- **Write** results to Kafka, files, webhooks, or databases

No code changes are needed to support a new file layout — register a spec, upload a file.

---

## 2. Repository Layout

```
transform-platform/
├── .docker/                        # All Docker/infrastructure files
│   ├── docker-compose.yml          # Local infra: Postgres, Kafka, Zookeeper, Kafka UI
│   ├── Dockerfile                  # Two-stage build for platform-api
│   └── env.example                 # Environment variable reference (safe to commit)
├── .run/                           # IntelliJ shared run configurations (committed)
│   ├── run-transform-app-local-config.xml      # Spring Boot app run config
│   └── run-transform-app-local-dependencies.xml # Docker Compose run config
├── .idea/runConfigurations/        # Legacy IntelliJ run configs (also committed)
├── platform-common/                # Shared models, no Spring dependencies
├── platform-core/                  # Engine: parsers, validators, transformers, writers
│   └── src/
│       ├── main/kotlin/com/transformplatform/core/
│       │   ├── parsers/            # FileParser interface + CSV, FixedWidth, XML impls
│       │   ├── pipeline/           # TransformationPipeline orchestrator
│       │   ├── spec/               # FileSpec model + ParserRegistry
│       │   ├── transformers/       # CorrectionEngine
│       │   ├── validators/         # ValidationEngine
│       │   └── writers/            # RecordWriter interface + KafkaRecordWriter
│       └── test/                   # Kotest tests (53 tests, all passing)
├── platform-api/                   # Spring Boot REST API (the only runnable module)
├── platform-pipeline/              # Spring Batch jobs (no source yet)
├── platform-scheduler/             # Quartz scheduler (no source yet)
├── SKILL.md                        # Developer runbook
├── AGENTS.md                       # This file
└── README.md                       # Project overview
```

---

## 3. Technology Stack

| Concern | Choice | Version |
|---------|--------|---------|
| Language | Kotlin | 1.9.22 |
| Runtime | JDK | 21 |
| Framework | Spring Boot | 3.2.3 |
| Build | Gradle (Kotlin DSL) | 8.6 |
| Streaming | Kotlin Coroutines / Flow | 1.7.3 |
| Messaging | Spring Kafka | (managed by Boot BOM) |
| Database | PostgreSQL + Flyway | (managed by Boot BOM) |
| API docs | SpringDoc OpenAPI | 2.3.0 |
| Auth | JJWT | 0.12.3 |
| Testing | Kotest | 5.8.1 |
| Mocking | MockK | 1.13.9 |
| Logging | kotlin-logging (mu) | 3.0.5 |

**Dependency version rule**: Spring Boot BOM manages most versions. Only override
when a specific version is required. Never add `flyway-database-postgresql` — it does
not exist in Flyway 9.x (the version Boot 3.2.3 manages).

---

## 4. Core Design Principles

### Open/Closed
Adding a new parser or writer means implementing one interface and annotating it
`@Component`. Nothing else changes. Never modify `ParserRegistry` or
`TransformationPipeline` to hard-code a new type.

### Spec-Driven
All parsing behaviour is declared in `FileSpec`. Business logic lives in specs,
not in code. If a new field extraction rule is needed, add a `CorrectionType` or
`RuleType` enum value and handle it in `CorrectionEngine`/`ValidationEngine`.

### Stream-First
Files are never loaded fully into memory. Every parser produces a
`Flow<ParsedRecord>`. Never collect an entire flow into a list inside production code.

### Fail-Safe
Errors are collected on each `ParsedRecord` — the pipeline never throws on a bad
record. `FATAL` severity stops that record. Everything else flows through with errors
attached. Callers decide what to do with invalid records.

### Security-First
Fields marked `sensitive = true` in `FieldSpec` must never appear in logs or error
messages. Use `"***"` as the masked placeholder. This pattern is already in
`CsvFileParser` and `FixedWidthFileParser` — follow it everywhere.

---

## 5. Architecture — How the Pipeline Works

```
InputStream
    │
    ▼
ParserRegistry.parse(input, spec)
    │   resolves FileParser by FileSpec.format
    │   returns Flow<ParsedRecord>
    ▼
CorrectionEngine.applyCorrections(record, spec)
    │   applies CorrectionRule list in applyOrder
    │   returns corrected ParsedRecord (immutable copy)
    ▼
ValidationEngine.validate(record, spec)
    │   runs ValidationRule list + per-field FieldSpec constraints
    │   attaches ParseError list to record (never throws)
    ▼
Filter (inside TransformationPipeline)
    │   FATAL errors  → skip, increment failedRecords
    │   skipInvalidRecords=true + errors → skip
    │   otherwise → pass through
    ▼
RecordWriter.write(record, request)
    │   resolved by PipelineDestination.type
    ▼
RecordWriter.flush(request)  ← called once after all records
    ▼
ProcessingResult
```

### Key classes

| Class | Package | Role |
|-------|---------|------|
| `FileSpec` | `core.spec.model` | Root spec — drives everything |
| `ParsedRecord` | `core.spec.model` | Universal record flowing through pipeline |
| `ParserRegistry` | `core.spec.registry` | Auto-discovers and routes to parsers |
| `TransformationPipeline` | `core.pipeline` | Orchestrates the full flow |
| `CorrectionEngine` | `core.transformers` | Applies `CorrectionRule` list |
| `ValidationEngine` | `core.validators` | Applies `ValidationRule` list |
| `KafkaRecordWriter` | `core.writers` | Writes `ParsedRecord` → Kafka as JSON |
| `TransformService` | `api.service` | Bridge: HTTP request → pipeline |
| `SpecService` | `api.service` | In-memory spec store (replace with JPA) |

---

## 6. Extension Points

### Adding a new parser

1. Create `platform-core/.../parsers/impl/YourFormatParser.kt`
2. Implement `FileParser` — must be `@Component`
3. Implement `supports(format)`, `parse(input, spec)`, optionally `validateSpec(spec)`
4. Add the format to `FileFormat` enum in `FileSpec.kt` if it's a new format
5. Write tests in `platform-core/src/test/` using `DescribeSpec`

```kotlin
@Component
class NachaFileParser : FileParser {
    override val parserName = "NACHA_PARSER"
    override fun supports(format: FileFormat) = format == FileFormat.NACHA
    override fun parse(input: InputStream, spec: FileSpec): Flow<ParsedRecord> = flow {
        // stream records, emit each ParsedRecord
    }
}
```

No changes to `ParserRegistry` or `TransformationPipeline`.

### Adding a new writer

1. Create `platform-core/.../writers/YourWriter.kt`
2. Implement `RecordWriter` — must be `@Component`
3. Implement `supports(destinationType)`, `write(record, request)`, optionally `flush(request)`
4. Add the type to `DestinationType` enum in `TransformationPipeline.kt` if it's new

```kotlin
@Component
class WebhookRecordWriter(private val webClient: WebClient) : RecordWriter {
    override val writerName = "WEBHOOK_WRITER"
    override fun supports(type: DestinationType) = type == DestinationType.WEBHOOK
    override suspend fun write(record: ParsedRecord, request: PipelineRequest) { ... }
}
```

### Adding a new correction type

1. Add the enum value to `CorrectionType` in `FileSpec.kt`
2. Add the `when` branch in `CorrectionEngine.applyCorrection()`
3. Add tests in `CorrectionEngineTest` using `ShouldSpec`

### Adding a new validation rule type

1. Add the enum value to `RuleType` in `FileSpec.kt`
2. Add the `when` branch in `ValidationEngine.checkRule()`
3. Add tests in `ValidationEngineTest` using `BehaviorSpec`

---

## 7. Code Conventions

### Kotlin style
- Data classes for all models — keep them immutable; use `.copy()` for mutations
- `private val log = KotlinLogging.logger {}` at file level, not class level
- `@Component` beans only — no manual `@Bean` factory methods unless unavoidable
- Coroutines: `suspend fun` for I/O, `Flow<T>` for streams; never `runBlocking` in production paths (only in `TransformService` as a bridge until WebFlux is threaded through)
- String templates over concatenation
- Named arguments for data class constructors with more than 3 parameters

### Error handling
- Never throw inside a parser's `Flow` — catch, create `ParseError`, add to record
- Fatal infrastructure errors (DB down, Kafka unreachable) may throw — the pipeline catches and returns `ProcessingStatus.FAILED`
- Log warnings with `log.warn { }`, never `println`

### Sensitive data
- Check `fieldSpec.sensitive` before including raw values in any error message or log
- Use `"***"` as the masked value placeholder — consistent across all parsers

### Package structure
```
com.transformplatform.
├── api.controller      REST controllers
├── api.dto             Request/response DTOs
├── api.service         Service layer
├── core.parsers        FileParser interface
├── core.parsers.impl   Format-specific parsers
├── core.pipeline       TransformationPipeline + request/destination models
├── core.spec.model     FileSpec, ParsedRecord, all enums
├── core.spec.registry  ParserRegistry
├── core.transformers   CorrectionEngine
├── core.validators     ValidationEngine
└── core.writers        RecordWriter interface + implementations
```

---

## 8. Testing Requirements

- **Framework**: Kotest only — no JUnit test classes
- **Minimum**: every new parser, writer, correction type, and validation rule type needs tests
- **Spec style guide**:

| What you're testing | Kotest style |
|---------------------|-------------|
| Parser behaviour | `DescribeSpec` |
| Correction rules | `ShouldSpec` |
| Validation rules | `BehaviorSpec` (Given/When/Then) |
| Registry / routing | `FunSpec` |
| Pipeline integration | `ShouldSpec` or `FunSpec` |

- **Coroutine flows in tests**: use `.toList()` to collect, `runTest {}` for suspend tests
- **No Spring context in unit tests** — instantiate classes directly; `@SpringBootTest` only for integration tests
- Run before every commit: `./gradlew :platform-core:test`

---

## 9. Documentation Maintenance Rule

**This is mandatory. Every PR that changes code must also update documentation.**

| Change type | Files to update |
|-------------|----------------|
| New parser added | `SKILL.md` §6, `AGENTS.md` §6, `README.md` supported formats |
| New writer added | `SKILL.md` §7, `AGENTS.md` §6 |
| New correction/validation type | `AGENTS.md` §6 |
| New env variable | `SKILL.md` §1, `AGENTS.md` §3, `.docker/env.example`, `.run/run-transform-app-local-config.xml` |
| New module added | `AGENTS.md` §2 layout + §5 module table, `SKILL.md` §11, `README.md` |
| Pipeline stage changed | `AGENTS.md` §5 architecture diagram |
| New Gradle task or build change | `SKILL.md` §4 |
| Troubleshooting insight | `SKILL.md` §12 |
| IntelliJ / run config change | `SKILL.md` §1, `.run/` files |

---

## 10. Pull Request Checklist

Before opening a PR, verify:

- [ ] `./gradlew build` passes (all modules, all tests)
- [ ] New code has tests — parsers use `DescribeSpec`, validators use `BehaviorSpec`
- [ ] Sensitive fields masked in all new log/error messages (`***` placeholder)
- [ ] No `runBlocking` added in production code paths outside `TransformService`
- [ ] No new `@Bean` factories unless justified — use `@Component`
- [ ] `SKILL.md` updated if behaviour or setup steps changed
- [ ] `AGENTS.md` updated if architecture or extension points changed
- [ ] `.docker/env.example` updated if new env variables were added
- [ ] `.run/run-transform-app-local-config.xml` updated if new env variables were added
- [ ] PR description explains the "why", not just the "what"

---

## 11. What NOT to Do

| Do not | Reason |
|--------|--------|
| Add `flyway-database-postgresql` dependency | Does not exist in Flyway 9.x (Boot 3.2.3 BOM) |
| Enable `bootJar` on `platform-pipeline` or `platform-scheduler` | No main class — will fail build |
| Use `type="SpringBootApplicationConfigurationType"` in `.run/` XML | Wrong type ID — IntelliJ will silently ignore the file; use `SpringBootApplicationRunConfiguration` |
| Load an entire file into a `List` in a parser | Breaks stream-first design; use `Flow` and `emit` |
| Log or include raw values for `sensitive=true` fields | PII leak; always mask with `***` |
| Throw exceptions inside `Flow { }` blocks in parsers | Creates uncollectable flow; add `ParseError` to record instead |
| Commit a filled `.env` file | Gitignored for a reason — use `env.example` as reference |
| Use JUnit test classes | Project is Kotest-only; JUnit Vintage is excluded from the test classpath |
| Modify `ParserRegistry` to hard-code a new parser | Breaks Open/Closed; just annotate the new parser `@Component` |
