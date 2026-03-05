---
id: platform-core
title: platform-core
sidebar_position: 3
---

# platform-core

The heart of the platform. Contains all transformation logic — parsers, validators, transformers, and writers. Has 53 passing tests.

## Package Layout

```
com.transformplatform.core
├── parsers/                    # FileParser interface
│   └── impl/                  # CsvFileParser, FixedWidthFileParser, XmlFileParser
├── pipeline/                  # TransformationPipeline + PipelineRequest, PipelineDestination
├── spec/
│   ├── model/                 # FileSpec, FieldSpec, ParsedRecord, CorrectionRule, ValidationRule
│   └── registry/              # ParserRegistry — auto-discovers parsers via @Component
├── transformers/              # CorrectionEngine
├── validators/                # ValidationEngine
└── writers/                   # RecordWriter interface + KafkaRecordWriter
```

## Key Interfaces

### FileParser

```kotlin
interface FileParser {
    val parserName: String
    fun supports(format: FileFormat): Boolean
    fun parse(input: InputStream, spec: FileSpec): Flow<ParsedRecord>
    fun validateSpec(spec: FileSpec) { }  // optional override
}
```

Implement and annotate `@Component` — auto-discovered by `ParserRegistry`.

### RecordWriter

```kotlin
interface RecordWriter {
    val writerName: String
    fun supports(type: DestinationType): Boolean
    suspend fun write(record: ParsedRecord, request: PipelineRequest)
    suspend fun flush(request: PipelineRequest) { }  // optional override
}
```

## Test Coverage

| Test suite | Kotest style | Count |
|-----------|-------------|-------|
| `CsvFileParserTest` | `DescribeSpec` | ~15 |
| `FixedWidthFileParserTest` | `DescribeSpec` | ~10 |
| `XmlFileParserTest` | `DescribeSpec` | ~10 |
| `CorrectionEngineTest` | `ShouldSpec` | ~8 |
| `ValidationEngineTest` | `BehaviorSpec` | ~10 |
| `ParserRegistryTest` | `FunSpec` | ~5 |
| `TransformationPipelineTest` | `ShouldSpec` | ~5 |

Run with: `./gradlew :platform-core:test`
