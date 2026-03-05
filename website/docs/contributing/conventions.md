---
id: conventions
title: Code Conventions
sidebar_position: 1
---

# Code Conventions

## Kotlin Style

- **Data classes for all models** — keep them immutable; use `.copy()` for mutations
- **Logging** — `private val log = KotlinLogging.logger {}` at file level, not class level
- **Beans** — `@Component` only; no manual `@Bean` factory methods unless unavoidable
- **Coroutines** — `suspend fun` for I/O, `Flow<T>` for streams; never `runBlocking` in production paths
- **Strings** — use string templates over concatenation
- **Named arguments** — required for data class constructors with more than 3 parameters

## Error Handling

- **Never throw** inside a parser's `Flow` — catch, create a `ParseError`, add it to the record
- **Fatal infrastructure errors** (DB down, Kafka unreachable) may throw — the pipeline catches them and returns `ProcessingStatus.FAILED`
- **Logging** — `log.warn { }` for warnings; never `println`

## Sensitive Data

- Check `fieldSpec.sensitive` before including raw values in any error message or log
- Always use `"***"` as the masked value placeholder — consistent across all parsers
- This pattern is already established in `CsvFileParser` and `FixedWidthFileParser`

## Package Structure

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

## What NOT To Do

| Do not | Reason |
|--------|--------|
| Add `flyway-database-postgresql` dependency | Does not exist in Flyway 9.x (Boot 3.2.3 BOM) |
| Enable `bootJar` on `platform-pipeline` or `platform-scheduler` | No main class — will fail build |
| Load an entire file into a `List` in a parser | Breaks stream-first design; use `Flow` and `emit` |
| Log or include raw values for `sensitive=true` fields | PII leak; always mask with `***` |
| Throw exceptions inside `Flow { }` blocks in parsers | Creates uncollectable flow; add `ParseError` to record instead |
| Commit a filled `.env` file | Gitignored for a reason — use `env.example` as reference |
| Use JUnit test classes | Project is Kotest-only; JUnit Vintage is excluded |
| Modify `ParserRegistry` to hard-code a new parser | Breaks Open/Closed; annotate the new parser `@Component` |
