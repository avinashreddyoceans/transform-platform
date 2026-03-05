---
id: platform-core
title: platform-core
sidebar_position: 3
---

# platform-core

The heart of the platform. Contains all transformation logic — parsers, validators, transformers, and writers. 53 passing Kotest tests.

## Package Structure

```mermaid
graph TD
    subgraph core["com.transformplatform.core"]
        SPEC["spec/\nmodel — FileSpec, FieldSpec, ParsedRecord\nCorrectionRule, ValidationRule, enums\nregistry — ParserRegistry"]
        PARSERS["parsers/\nFileParser interface\nimpl/ — CsvFileParser\n        FixedWidthFileParser\n        XmlFileParser"]
        PIPELINE["pipeline/\nTransformationPipeline\nPipelineRequest\nPipelineDestination"]
        TRANSFORM["transformers/\nCorrectionEngine"]
        VALID["validators/\nValidationEngine"]
        WRITERS["writers/\nRecordWriter interface\nKafkaRecordWriter"]
    end

    SPEC --> PARSERS
    SPEC --> PIPELINE
    PARSERS --> PIPELINE
    TRANSFORM --> PIPELINE
    VALID --> PIPELINE
    WRITERS --> PIPELINE

    style SPEC fill:#dbeafe,stroke:#2563eb
    style PIPELINE fill:#dcfce7,stroke:#16a34a
```

## Key Interfaces

### FileParser — how parsers plug in

```mermaid
classDiagram
    class FileParser {
        <<interface>>
        +parserName: String
        +supports(format: FileFormat) Boolean
        +parse(input: InputStream, spec: FileSpec) Flow~ParsedRecord~
        +validateSpec(spec: FileSpec)
    }
    class CsvFileParser {
        +parserName = "CSV_PARSER"
        +supports(format) Boolean
        +parse(input, spec) Flow~ParsedRecord~
    }
    class FixedWidthFileParser {
        +parserName = "FIXED_WIDTH_PARSER"
        +supports(format) Boolean
        +parse(input, spec) Flow~ParsedRecord~
    }
    class XmlFileParser {
        +parserName = "XML_PARSER"
        +supports(format) Boolean
        +parse(input, spec) Flow~ParsedRecord~
    }
    FileParser <|.. CsvFileParser
    FileParser <|.. FixedWidthFileParser
    FileParser <|.. XmlFileParser
```

### RecordWriter — how writers plug in

```mermaid
classDiagram
    class RecordWriter {
        <<interface>>
        +writerName: String
        +supports(type: DestinationType) Boolean
        +write(record: ParsedRecord, request: PipelineRequest)
        +flush(request: PipelineRequest)
    }
    class KafkaRecordWriter {
        +writerName = "KAFKA_WRITER"
        +supports(type) Boolean
        +write(record, request)
        +flush(request)
    }
    RecordWriter <|.. KafkaRecordWriter
```

## Test Coverage

```mermaid
pie title Test Distribution (53 tests)
    "Parser tests" : 35
    "CorrectionEngine" : 8
    "ValidationEngine" : 10
```

| Test suite | Kotest style | Count |
|-----------|-------------|-------|
| `CsvFileParserTest` | `DescribeSpec` | ~15 |
| `FixedWidthFileParserTest` | `DescribeSpec` | ~10 |
| `XmlFileParserTest` | `DescribeSpec` | ~10 |
| `CorrectionEngineTest` | `ShouldSpec` | ~8 |
| `ValidationEngineTest` | `BehaviorSpec` | ~10 |
| `ParserRegistryTest` | `FunSpec` | ~5 |
| `TransformationPipelineTest` | `ShouldSpec` | ~5 |

```bash
./gradlew :platform-core:test
```
