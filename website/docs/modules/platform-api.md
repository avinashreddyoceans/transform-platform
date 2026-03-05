---
id: platform-api
title: platform-api
sidebar_position: 4
---

# platform-api

The runnable Spring Boot module. Exposes a REST API for spec management, file uploads, and transform orchestration.

## Layer Diagram

```mermaid
flowchart TB
    subgraph http["HTTP Layer"]
        SC[SpecController]
        TC[TransformController]
    end

    subgraph svc["Service Layer"]
        SS[SpecService\nin-memory spec store]
        TS[TransformService\nHTTP → pipeline bridge]
    end

    subgraph core["platform-core"]
        PIPE[TransformationPipeline]
    end

    subgraph infra["Infrastructure"]
        PG[(PostgreSQL)]
        KF[Kafka]
    end

    SC --> SS
    TC --> TS
    SS -.->|future: JPA| PG
    TS --> PIPE
    PIPE --> KF

    style http fill:#dbeafe,stroke:#2563eb
    style svc fill:#dcfce7,stroke:#16a34a
    style core fill:#f3f4f6,stroke:#6b7280
    style infra fill:#fef9c3,stroke:#ca8a04
```

## Startup

```bash
./gradlew :platform-api:bootRun
```

Swagger UI: `http://localhost:8080/swagger-ui`

## Request Flow — File Transform

```mermaid
sequenceDiagram
    participant Client
    participant TC as TransformController
    participant TS as TransformService
    participant SS as SpecService
    participant PIPE as TransformationPipeline

    Client->>TC: POST /transform/file-to-events\n(multipart file + specId + kafkaTopic)
    TC->>TS: transform(file, specId, destination)
    TS->>SS: getSpec(specId)
    SS-->>TS: FileSpec
    TS->>PIPE: process(inputStream, spec, request)
    Note over PIPE: parse → correct → validate → write
    PIPE-->>TS: ProcessingResult
    TS-->>TC: ProcessingResult
    TC-->>Client: 200 OK
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/transform` | Database URL |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `JWT_SECRET` | *(required)* | HMAC secret for JWT signing |

See `.docker/env.example` for the full list.

## API Documentation

Interactive docs at `/swagger-ui` via SpringDoc OpenAPI 2.3.0. See [API Reference](/api-reference) for all endpoints.
