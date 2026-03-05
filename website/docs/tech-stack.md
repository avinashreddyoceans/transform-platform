---
id: tech-stack
title: Tech Stack
sidebar_position: 7
---

# Tech Stack

| Concern | Choice | Version |
|---------|--------|---------|
| Language | Kotlin | 1.9.22 |
| Runtime | JDK | 21 |
| Framework | Spring Boot | 3.2.3 |
| Build | Gradle (Kotlin DSL) | 8.6 |
| Streaming | Kotlin Coroutines / Flow | 1.7.3 |
| Messaging | Spring Kafka | *(managed by Boot BOM)* |
| Database | PostgreSQL + Flyway | *(managed by Boot BOM)* |
| API Docs | SpringDoc OpenAPI | 2.3.0 |
| Auth | JJWT | 0.12.3 |
| Testing | Kotest | 5.8.1 |
| Mocking | MockK | 1.13.9 |
| Logging | kotlin-logging (mu) | 3.0.5 |

## Version Policy

Spring Boot BOM manages most dependency versions. Only override when a specific version is required.

:::danger Common Pitfall
Never add `flyway-database-postgresql` as a dependency. It does not exist in Flyway 9.x (the version managed by Boot 3.2.3 BOM). The `flyway-core` dependency is sufficient.
:::

## Key Design Decisions

### Kotlin Coroutines + Flow

Files are processed as `Flow<ParsedRecord>` streams. This means:

- **Any file size** — a 10 GB file uses the same memory as a 1 KB file
- **Backpressure** — the pipeline only requests records as fast as the writer can consume them
- **Structured concurrency** — coroutine scope is tied to the HTTP request lifecycle

### Spring Boot BOM

Avoids version conflicts by letting the Boot BOM resolve compatible versions for Kafka, Flyway, Jackson, etc. Only override when absolutely necessary and document why.

### Kotest (not JUnit)

The `platform-core` test suite uses Kotest exclusively. JUnit Vintage is excluded from the test classpath. Always use the appropriate Kotest spec style for the type of test you're writing — see [Testing Requirements](/contributing/testing).
