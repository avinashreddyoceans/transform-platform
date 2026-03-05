---
id: conventions
title: Code Conventions
sidebar_position: 1
---

# Code Conventions

## Error Handling in Parsers

```mermaid
flowchart TD
    E{Exception in\nparser Flow block?}
    E -->|Yes| CATCH[Catch the exception]
    CATCH --> PE[Create ParseError\nwith ruleId + message + severity]
    PE --> ADD[Add to ParsedRecord.errors]
    ADD --> EMIT[emit the record]

    E -->|Infrastructure error\nDB down / Kafka offline| THROW[May throw\nPipeline catches it\nReturns FAILED status]

    style CATCH fill:#fef9c3,stroke:#ca8a04
    style THROW fill:#fee2e2,stroke:#ef4444
    style EMIT fill:#dcfce7,stroke:#16a34a
```

:::danger
Never throw inside a `Flow { }` block in a parser. Exceptions inside a flow create uncollectable flows and break the entire stream.
:::

## Sensitive Data Masking

```mermaid
flowchart LR
    F[Field value] --> CHECK{fieldSpec.sensitive?}
    CHECK -->|true| MASK["Log / error message\nuses \"***\""]
    CHECK -->|false| RAW[Log / error message\nuses raw value]

    style MASK fill:#dcfce7,stroke:#16a34a
    style RAW fill:#f3f4f6,stroke:#6b7280
```

Always check `fieldSpec.sensitive` before including a value in any log or error message. Use `"***"` as the placeholder.

## Kotlin Style

- **Data classes for all models** — keep them immutable; use `.copy()` for mutations
- **Logging** — `private val log = KotlinLogging.logger {}` at file level, not class level
- **Beans** — `@Component` only; no manual `@Bean` factory methods unless unavoidable
- **Coroutines** — `suspend fun` for I/O, `Flow<T>` for streams; never `runBlocking` in production paths
- **Strings** — use string templates over concatenation
- **Named arguments** — required for data class constructors with more than 3 parameters

## Package Structure

```mermaid
graph TD
    ROOT["com.transformplatform"]
    ROOT --> API["api"]
    ROOT --> CORE["core"]
    API --> CTRL["api.controller\nREST controllers"]
    API --> DTO["api.dto\nRequest / response DTOs"]
    API --> SVC["api.service\nTransformService, SpecService"]
    CORE --> PARSERS["core.parsers\nFileParser interface"]
    CORE --> IMPL["core.parsers.impl\nFormat-specific parsers"]
    CORE --> PIPE["core.pipeline\nTransformationPipeline"]
    CORE --> SPEC["core.spec.model\nFileSpec, ParsedRecord, enums"]
    CORE --> REG["core.spec.registry\nParserRegistry"]
    CORE --> TRANS["core.transformers\nCorrectionEngine"]
    CORE --> VALID["core.validators\nValidationEngine"]
    CORE --> WRIT["core.writers\nRecordWriter + implementations"]

    style API fill:#dbeafe,stroke:#2563eb
    style CORE fill:#dcfce7,stroke:#16a34a
```

## What NOT To Do

| Do not | Reason |
|--------|--------|
| Add `flyway-database-postgresql` dependency | Does not exist in Flyway 9.x (Boot 3.2.3 BOM) |
| Enable `bootJar` on `platform-pipeline` or `platform-scheduler` | No main class — will fail build |
| Load an entire file into a `List` in a parser | Breaks stream-first design; use `Flow` and `emit` |
| Log raw values for `sensitive=true` fields | PII leak; always mask with `***` |
| Throw exceptions inside `Flow { }` blocks | Creates uncollectable flow; add `ParseError` to record instead |
| Commit a filled `.env` file | Gitignored for a reason — use `env.example` as reference |
| Use JUnit test classes | Project is Kotest-only; JUnit Vintage is excluded |
| Modify `ParserRegistry` to hard-code a new parser | Breaks Open/Closed; annotate the new parser `@Component` |
