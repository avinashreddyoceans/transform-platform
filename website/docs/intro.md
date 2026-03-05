---
id: intro
title: Introduction
slug: /
sidebar_position: 1
---

# Transform Platform

**Enterprise-grade, spec-driven file ↔ event transformation engine.**

Transform Platform lets you parse, validate, correct, and route any file format — without writing code. Define a `FileSpec`, upload a file, and the platform handles the rest.

## How It Works

```mermaid
flowchart LR
    FS["🗂 FileSpec\n(your JSON schema)"] -->|drives| PP

    subgraph PP["Transformation Pipeline"]
        direction LR
        P[Parse] --> C[Correct] --> V[Validate] --> W[Write]
    end

    FI["📄 File Upload"] --> P
    W --> K["📨 Kafka Events"]
    W --> DB["🗄 Database"]
    W --> WH["🔗 Webhook"]

    style FS fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    style K fill:#dcfce7,stroke:#16a34a
    style DB fill:#dcfce7,stroke:#16a34a
    style WH fill:#dcfce7,stroke:#16a34a
```

## Key Principles

```mermaid
mindmap
  root((Transform Platform))
    Spec-Driven
      FileSpec defines everything
      Business logic in specs not code
      No deploys for new formats
    Stream-First
      Flow of ParsedRecord
      Any file size
      No full load into memory
    Open/Closed
      Implement one interface
      Annotate @Component
      Zero changes to core
    Fail-Safe
      Errors attach to records
      Pipeline never stops on bad data
      FATAL severity skips record
    Security-First
      Sensitive fields masked in logs
      Encryption at rest and in transit
```

## Supported Formats

```mermaid
timeline
    title Format Rollout
    Phase 1 : CSV / Delimited
            : Fixed-Width / Flat File
            : XML with XPath mapping
    Phase 2 : JSON
            : NACHA
            : ISO 20022
```

## Next Steps

- [Getting Started](/getting-started) — run the platform locally in minutes
- [Architecture](/architecture) — understand the transformation pipeline
- [API Reference](/api-reference) — REST endpoints for spec management and file transforms
