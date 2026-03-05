---
id: architecture
title: Architecture
sidebar_position: 3
---

# Architecture

## High-Level Data Flow

```mermaid
flowchart LR
    subgraph Inbound["📥 File → Events"]
        FI[📄 File In] --> PA[Parser]
        PA --> CO[Correct]
        CO --> VA[Validate]
        VA --> KA[📨 Kafka Events Out]
    end

    subgraph Outbound["📤 Events → File"]
        EI[📨 Events In] --> AG[Aggregator]
        AG --> FB[File Builder]
        FB --> FO[📄 File Out]
    end

    SR[(🗂 Spec Registry)] -.->|drives| PA
    SR -.->|drives| CO
    SR -.->|drives| VA
```

## Transformation Pipeline (Detail)

Each record flows through five deterministic stages. No stage throws — errors accumulate on the record.

```mermaid
flowchart TD
    A([InputStream]) --> B

    B["🔍 ParserRegistry.parse()\nResolves FileParser by format\nReturns Flow of ParsedRecord"]
    B --> C

    C["✏️ CorrectionEngine.applyCorrections()\nApplies CorrectionRule list in applyOrder\nReturns immutable corrected copy"]
    C --> D

    D["✅ ValidationEngine.validate()\nRuns ValidationRule list + FieldSpec constraints\nAttaches ParseError list — never throws"]
    D --> E

    E{Filter}

    E -->|FATAL errors| F[⏭ Skip record\nfailedRecords++]
    E -->|skipInvalidRecords + errors| F
    E -->|passes filter| G

    G["💾 RecordWriter.write()\nResolved by PipelineDestination.type"]
    G --> H

    H["🔁 RecordWriter.flush()\nCalled once after all records"]
    H --> I([ProcessingResult])

    style F fill:#fee2e2,stroke:#ef4444
    style I fill:#dcfce7,stroke:#22c55e
```

## Error Severity Routing

```mermaid
flowchart LR
    E([ParseError]) --> S{Severity?}

    S -->|WARNING| W["Flows through\nError attached for reporting"]
    S -->|ERROR| ER{"skipInvalidRecords?"}
    ER -->|true| SK[Record skipped]
    ER -->|false| TH["Flows through\nError attached"]
    S -->|FATAL| FA["Always skipped\nfailedRecords++"]

    style SK fill:#fef9c3,stroke:#ca8a04
    style FA fill:#fee2e2,stroke:#ef4444
    style W fill:#dcfce7,stroke:#22c55e
    style TH fill:#dcfce7,stroke:#22c55e
```

## Open/Closed Extension Model

Add a parser or writer by implementing one interface + `@Component`. Spring discovers it automatically — zero changes to `ParserRegistry` or `TransformationPipeline`.

```mermaid
flowchart LR
    subgraph interfaces["Interfaces in platform-core"]
        FP[FileParser]
        RW[RecordWriter]
    end

    subgraph your["Your new code"]
        NP["@Component NachaFileParser\nimplements FileParser"]
        NW["@Component WebhookWriter\nimplements RecordWriter"]
    end

    subgraph auto["Auto-wired by Spring"]
        PR[ParserRegistry]
        TP[TransformationPipeline]
    end

    NP -.->|implements| FP
    NW -.->|implements| RW
    FP --> PR
    RW --> TP
    PR --> TP
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
