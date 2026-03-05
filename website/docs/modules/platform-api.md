---
id: platform-api
title: platform-api
sidebar_position: 4
---

# platform-api

The runnable Spring Boot module. Exposes a REST API for spec management, file uploads, and transform orchestration.

## Startup

```bash
./gradlew :platform-api:bootRun
```

Swagger UI: `http://localhost:8080/swagger-ui`

## Package Layout

```
com.transformplatform
├── api.controller      # REST controllers
├── api.dto             # Request/response DTOs
└── api.service         # TransformService, SpecService
```

## Key Services

### TransformService

Bridge between the HTTP layer and `TransformationPipeline`. Receives multipart file + spec ID, constructs a `PipelineRequest`, and calls the pipeline. Uses `runBlocking` as a temporary bridge (to be replaced when WebFlux is fully threaded through).

### SpecService

In-memory spec store keyed by UUID. Replace with a JPA-backed repository for production persistence.

## Environment Variables

See `.docker/env.example` for the full list. Key variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/transform` | Database URL |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `JWT_SECRET` | *(required)* | HMAC secret for JWT signing |

## API Documentation

Interactive API docs are available at `/swagger-ui` via SpringDoc OpenAPI 2.3.0. See [API Reference](/api-reference) for endpoint details.
