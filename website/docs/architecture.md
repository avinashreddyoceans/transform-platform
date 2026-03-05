---
id: architecture
title: Architecture
sidebar_position: 3
---

# Architecture

## High-Level Data Flow

```
[File In]  →  [Parser]  →  [Correct]  →  [Validate]  →  [Kafka Events Out]
[Events In] → [Aggregator] → [File Builder] → [File Out]
                    ↑
         [Spec Registry — user-defined schemas]
```

## Pipeline Detail

Every file transformation follows the same sequence of stages, all driven by a single `FileSpec`:

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

## Key Classes

| Class | Package | Role |
|-------|---------|------|
| `FileSpec` | `core.spec.model` | Root spec — drives everything |
| `ParsedRecord` | `core.spec.model` | Universal record flowing through the pipeline |
| `ParserRegistry` | `core.spec.registry` | Auto-discovers parsers and routes by format |
| `TransformationPipeline` | `core.pipeline` | Orchestrates the full flow |
| `CorrectionEngine` | `core.transformers` | Applies the `CorrectionRule` list |
| `ValidationEngine` | `core.validators` | Applies the `ValidationRule` list |
| `KafkaRecordWriter` | `core.writers` | Writes `ParsedRecord` → Kafka as JSON |
| `TransformService` | `api.service` | Bridge: HTTP request → pipeline |
| `SpecService` | `api.service` | In-memory spec store (replace with JPA for production) |

## FileSpec — The Central Abstraction

A `FileSpec` is a single JSON document that fully describes a file format. It contains:

- **`format`** — `CSV`, `FIXED_WIDTH`, `XML`, `JSON`, `NACHA`, `ISO_20022`
- **`fields`** — list of `FieldSpec` (name, type, column/position, sensitive flag, format pattern)
- **`correctionRules`** — ordered list of `CorrectionRule` applied before validation
- **`validationRules`** — list of `ValidationRule` applied after correction
- **`hasHeader`**, **`delimiter`**, **`encoding`** — format-specific options

## ParsedRecord — Universal Row

Every parser emits `ParsedRecord` objects regardless of the source format. Each record carries:

- `fields: Map<String, Any?>` — the parsed field values
- `errors: List<ParseError>` — validation/correction errors (empty = clean record)
- `rowNumber: Long` — 1-based row index

The pipeline never modifies the original `ParsedRecord` — corrections produce immutable copies via `.copy()`.

## Fail-Safe Error Handling

The pipeline **never throws** on a bad record. Errors are attached to the record and the pipeline continues:

| Error severity | Pipeline behaviour |
|----------------|-------------------|
| `ERROR` | Attached to record; record still flows through (unless `skipInvalidRecords=true`) |
| `FATAL` | Record is skipped; `failedRecords` counter incremented |

Fatal infrastructure errors (DB down, Kafka unreachable) propagate up and result in `ProcessingStatus.FAILED`.

## Open/Closed Extension Model

New parsers and writers are discovered automatically via Spring's component scan:

```kotlin
@Component
class NachaFileParser : FileParser {
    override fun supports(format: FileFormat) = format == FileFormat.NACHA
    // ...
}
```

`ParserRegistry` and `TransformationPipeline` never need to be modified. See [Extending the Platform](/extending/adding-a-parser) for step-by-step guides.
