---
id: overview
title: Integration Domain Overview
sidebar_position: 1
---

# Integration Domain

The Integration Domain is the layer that knows **how to move data** to and from the outside world. It is client-aware, dynamically configurable at runtime, and credential-safe.

## Why a Separate Domain?

The existing pipeline knows how to **transform** data. It does not know which bank's SFTP server to pull from, which Kafka cluster a client is subscribed to, or what OAuth token to use for a REST webhook. That knowledge belongs here — the Integration Domain.

```mermaid
flowchart LR
    subgraph outside["External World"]
        SFTP[🖥 SFTP Servers]
        KAFKA[📨 Kafka Clusters]
        REST[🌐 REST Endpoints]
        S3[☁️ S3 / Cloud Storage]
    end

    subgraph integration["Integration Domain (new)"]
        REG[IntegrationRegistry]
        CONN[Connectors\nSftp · Kafka · Rest · S3]
        CRED[CredentialStore\nAES-256 encrypted]
        REG --> CONN
        CRED --> CONN
    end

    subgraph core["Existing Platform"]
        PIPE[TransformationPipeline]
        WRITER[RecordWriter]
    end

    subgraph onboard["Onboarding API"]
        API[POST /integrations\nPUT /integrations/:id]
    end

    SFTP <--> CONN
    KAFKA <--> CONN
    REST <--> CONN
    S3 <--> CONN

    API -->|creates / updates| REG
    PIPE --> WRITER --> REG

    style integration fill:#dbeafe,stroke:#2563eb
    style outside fill:#f3f4f6,stroke:#6b7280
    style core fill:#dcfce7,stroke:#16a34a
    style onboard fill:#fef9c3,stroke:#ca8a04
```

## Core Concepts

| Concept | What it is |
|---------|-----------|
| **Client** | A tenant — a bank, insurer, or partner that uses the platform |
| **ClientIntegration** | One configured integration for one client (e.g., "Bank A outbound SFTP") |
| **IntegrationConnector** | The live, authenticated connection object held in memory |
| **IntegrationRegistry** | The in-memory map of all active connectors, hot-reloaded on change |
| **CredentialStore** | Encrypted storage for passwords, keys, tokens — never in plain text |
| **IntegrationDirection** | `INBOUND` (we pull/receive), `OUTBOUND` (we push/send), `BIDIRECTIONAL` |
