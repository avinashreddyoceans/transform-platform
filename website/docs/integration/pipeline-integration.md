---
id: pipeline-integration
title: Pipeline Integration
sidebar_position: 6
---

# How the Integration Domain Connects to the Pipeline

The existing `TransformationPipeline` is untouched. The Integration Domain hooks in through two extension points: the `RecordWriter` interface (outbound) and a new `FileIngestionService` (inbound).

## Current vs Extended Pipeline

```mermaid
flowchart LR
    subgraph existing["Existing Pipeline (unchanged)"]
        PARSE[Parse] --> CORRECT[Correct] --> VALIDATE[Validate] --> WRITER[RecordWriter]
    end

    subgraph integration["Integration Domain additions"]
        SFTP_W["SftpRecordWriter\nimplements RecordWriter"]
        S3_W["S3RecordWriter\nimplements RecordWriter"]
        REST_W["RestRecordWriter\nimplements RecordWriter"]
        REG[IntegrationRegistry]
    end

    WRITER -->|SFTP_OUTBOUND| SFTP_W
    WRITER -->|S3_OUTBOUND| S3_W
    WRITER -->|REST_WEBHOOK| REST_W

    SFTP_W --> REG
    S3_W --> REG
    REST_W --> REG

    style existing fill:#dcfce7,stroke:#16a34a
    style integration fill:#dbeafe,stroke:#2563eb
```

## New DestinationType Values

The `DestinationType` enum gains new values to cover all integration destinations:

```mermaid
graph TD
    DT[DestinationType enum]

    DT --> KT[KAFKA_TOPIC\nexisting]
    DT --> OF[OUTPUT_FILE\nexisting]
    DT --> WH[WEBHOOK\nexisting]
    DT --> DB[DATABASE\nexisting]
    DT --> SO["SFTP_OUTBOUND\nnew — push to client SFTP"]
    DT --> SI["SFTP_INBOUND_RESULT\nnew — write result back to source"]
    DT --> S3O["S3_OUTBOUND\nnew — push to S3 bucket"]
    DT --> RW["REST_WEBHOOK\nnew — POST to client endpoint"]

    style SO fill:#dbeafe,stroke:#2563eb
    style SI fill:#dbeafe,stroke:#2563eb
    style S3O fill:#dbeafe,stroke:#2563eb
    style RW fill:#dbeafe,stroke:#2563eb
    style KT fill:#f3f4f6,stroke:#6b7280
    style OF fill:#f3f4f6,stroke:#6b7280
    style WH fill:#f3f4f6,stroke:#6b7280
    style DB fill:#f3f4f6,stroke:#6b7280
```

## PipelineRequest Changes

`PipelineRequest` gains a `clientId` and an optional `deliveryIntegrationId`:

```mermaid
classDiagram
    class PipelineRequest {
        +spec: FileSpec
        +inputStream: InputStream
        +fileName: String
        +destination: PipelineDestination
        +skipInvalidRecords: Boolean
        +correlationId: String
        +clientId: UUID [NEW]
        +sourceIntegrationId: UUID? [NEW]
    }

    class PipelineDestination {
        +type: DestinationType
        +kafkaTopic: String?
        +outputFilePath: String?
        +webhookUrl: String?
        +integrationId: UUID? [NEW - resolves connector from registry]
    }
```

## Outbound Writer Resolution

```mermaid
sequenceDiagram
    participant PIPE as TransformationPipeline
    participant SFTPW as SftpRecordWriter
    participant REG as IntegrationRegistry
    participant CONN as SftpConnector

    PIPE->>SFTPW: write(record, request)
    Note over SFTPW: supports(SFTP_OUTBOUND) = true
    SFTPW->>REG: getOutboundConnector(request.clientId, SFTP)
    REG-->>SFTPW: SftpConnector
    SFTPW->>CONN: buffer record as CSV/file row

    Note over SFTPW: After all records:

    PIPE->>SFTPW: flush(request)
    SFTPW->>CONN: sendFile(assembledFile)
    CONN-->>SFTPW: transfer OK
```

## Inbound File Ingestion Flow

Inbound connectors are **not** driven by the pipeline — they drive the pipeline. A scheduled poller pulls files and feeds them in.

```mermaid
flowchart TD
    POLL([SftpInboundPoller\n@Scheduled or Quartz]) --> LIST[List files on SFTP]
    LIST --> DL[Download file]
    DL --> SPEC[Lookup client's FileSpec\nfor this integration]
    SPEC --> REQ[Build PipelineRequest\nclientId + sourceIntegrationId]
    REQ --> PIPE[TransformationPipeline.execute]
    PIPE --> RESULT{Result}
    RESULT -->|OK| ACK[Acknowledge file on SFTP\nMove to /processed]
    RESULT -->|FAILED| QUARANTINE[Move to /error\nPublish IngestionFailedEvent]

    style POLL fill:#dbeafe,stroke:#2563eb
    style ACK fill:#dcfce7,stroke:#16a34a
    style QUARANTINE fill:#fee2e2,stroke:#ef4444
```

## Complete End-to-End: SFTP-in → Transform → SFTP-out

```mermaid
sequenceDiagram
    participant SFTP_IN as Partner SFTP (inbound)
    participant POLLER as SftpInboundPoller
    participant PIPE as TransformationPipeline
    participant SFTP_OUT as Client SFTP (outbound)

    loop Every pollIntervalSeconds
        POLLER->>SFTP_IN: list /inbound/*.csv
        SFTP_IN-->>POLLER: [transactions_20240115.csv]
        POLLER->>SFTP_IN: download file
        SFTP_IN-->>POLLER: InputStream
        POLLER->>PIPE: execute(PipelineRequest)\nsource=SFTP_INBOUND, dest=SFTP_OUTBOUND
        Note over PIPE: Parse → Correct → Validate → SftpRecordWriter
        PIPE->>SFTP_OUT: upload transformed file
        SFTP_OUT-->>PIPE: OK
        PIPE-->>POLLER: ProcessingResult { processed=1000, failed=2 }
        POLLER->>SFTP_IN: move file → /processed
    end
```
