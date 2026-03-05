# Transform Platform

Enterprise-grade, spec-driven file ↔ event transformation engine.

## Architecture

```
[File In]  →  [Parser]  →  [Correct]  →  [Validate]  →  [Kafka Events Out]
[Events In] → [Aggregator] → [File Builder] → [File Out]
                    ↑
         [Spec Registry — user-defined schemas]
```

## Modules

| Module | Purpose |
|---|---|
| `platform-common` | Shared models, exceptions, utilities |
| `platform-core` | Spec engine, parsers, validators, transformers, writers |
| `platform-api` | REST API — spec management, file upload, transform orchestration |
| `platform-pipeline` | Spring Batch jobs for bulk processing |
| `platform-scheduler` | Quartz-based scheduling and delay engine |

## Supported Formats (Phase 1)

- CSV / Delimited (any delimiter)
- Fixed-Width / Flat File
- XML (with XPath field mapping, XSD validation)
- JSON (coming Phase 2)
- NACHA (coming Phase 2)
- ISO 20022 (coming Phase 2)

## Quick Start

### Prerequisites
- JDK 21+
- Docker & Docker Compose
- Gradle 8+

### Start infrastructure
```bash
docker-compose up -d
```

### Run the API
```bash
./gradlew :platform-api:bootRun
```

### Swagger UI
Open: http://localhost:8080/swagger-ui

### Run tests
```bash
./gradlew test
```

## Creating a Spec

```json
POST /api/v1/specs
{
  "name": "Bank Transactions CSV",
  "format": "CSV",
  "hasHeader": true,
  "delimiter": ",",
  "fields": [
    { "name": "accountNumber", "type": "STRING",  "columnName": "account_number", "sensitive": true },
    { "name": "amount",        "type": "DECIMAL", "columnName": "amount" },
    { "name": "transactionDate","type": "DATE",   "columnName": "date", "format": "yyyy-MM-dd" },
    { "name": "description",   "type": "STRING",  "columnName": "description", "required": false }
  ],
  "correctionRules": [
    { "ruleId": "trim-desc", "field": "description", "correctionType": "TRIM" }
  ],
  "validationRules": [
    { "ruleId": "amount-positive", "field": "amount", "ruleType": "MIN_VALUE", "value": "0", "message": "Amount must be positive", "severity": "ERROR" }
  ]
}
```

## Transforming a File

```bash
curl -X POST http://localhost:8080/api/v1/transform/file-to-events \
  -F "file=@transactions.csv" \
  -F "specId=<your-spec-id>" \
  -F "kafkaTopic=bank-transactions"
```

## Design Principles

- **Spec-Driven**: All parsing behaviour is defined by specs, not code. Add a new format by registering a spec.
- **Stream-First**: Files of any size are processed as a Flow — never loaded fully into memory.
- **Open/Closed**: Add new parsers or writers by implementing an interface. Zero changes to existing code.
- **Fail-Safe**: Errors are collected per-record; the pipeline continues unless a FATAL error is encountered.
- **Security-First**: Sensitive fields are masked in logs. Encryption at rest and in transit by design.
