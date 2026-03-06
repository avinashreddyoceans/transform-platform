---
id: module-structure
title: Module Structure & Implementation Plan
sidebar_position: 7
---

# Module Structure & Implementation Plan

## New Module: `platform-integration`

```mermaid
graph TD
    subgraph modules["Gradle Modules after this change"]
        API["platform-api"]
        INT["platform-integration ← NEW"]
        CORE["platform-core"]
        COMMON["platform-common"]
        PIPE["platform-pipeline"]
        SCHED["platform-scheduler"]
    end

    API --> INT
    API --> CORE
    INT --> CORE
    PIPE --> CORE
    SCHED --> CORE
    CORE --> COMMON
    INT --> COMMON

    style INT fill:#dbeafe,stroke:#2563eb
```

## Package Layout

```
platform-integration/
└── src/main/kotlin/com/transformplatform/integration/
    │
    ├── domain/                          # JPA entities
    │   ├── Client.kt                    # Tenant entity
    │   ├── ClientIntegration.kt         # Integration config entity
    │   ├── IntegrationCredential.kt     # Encrypted credential entity
    │   ├── IntegrationEventLog.kt       # Audit log entity
    │   ├── IntegrationType.kt           # enum: SFTP, KAFKA, REST, S3, FTP, AS2
    │   ├── IntegrationDirection.kt      # enum: INBOUND, OUTBOUND, BIDIRECTIONAL
    │   └── IntegrationStatus.kt         # enum: ACTIVE, INACTIVE, ERROR, TESTING
    │
    ├── config/                          # Type-specific config data classes
    │   ├── IntegrationConfig.kt         # sealed class — base
    │   ├── SftpIntegrationConfig.kt
    │   ├── KafkaIntegrationConfig.kt
    │   ├── RestIntegrationConfig.kt
    │   └── S3IntegrationConfig.kt
    │
    ├── connector/                       # Live connection objects
    │   ├── IntegrationConnector.kt      # interface
    │   ├── InboundConnector.kt          # interface
    │   ├── OutboundConnector.kt         # interface
    │   ├── sftp/
    │   │   ├── SftpConnector.kt         # implements both In + Out
    │   │   ├── SftpConnectionPool.kt    # Apache MINA SSHD pool
    │   │   └── SftpInboundPoller.kt     # @Scheduled polling task
    │   ├── kafka/
    │   │   └── KafkaConnector.kt
    │   ├── rest/
    │   │   ├── RestConnector.kt
    │   │   └── auth/
    │   │       ├── BasicAuthHandler.kt
    │   │       ├── BearerAuthHandler.kt
    │   │       └── OAuth2AuthHandler.kt
    │   └── s3/
    │       └── S3Connector.kt
    │
    ├── registry/
    │   ├── IntegrationRegistry.kt       # manages live connectors, handles events
    │   └── IntegrationFactory.kt        # creates connectors from config + credentials
    │
    ├── credential/
    │   ├── CredentialService.kt         # encrypt / decrypt
    │   └── AesGcmEncryption.kt          # AES-256-GCM implementation
    │
    ├── writers/                         # RecordWriter implementations for pipeline
    │   ├── SftpRecordWriter.kt          # implements RecordWriter, looks up registry
    │   ├── S3RecordWriter.kt
    │   └── RestRecordWriter.kt
    │
    ├── inbound/
    │   └── FileIngestionService.kt      # orchestrates inbound polling → pipeline
    │
    ├── repository/
    │   ├── ClientRepository.kt
    │   ├── ClientIntegrationRepository.kt
    │   └── IntegrationCredentialRepository.kt
    │
    ├── service/
    │   └── IntegrationService.kt        # CRUD + lifecycle management
    │
    └── events/
        ├── IntegrationCreatedEvent.kt
        ├── IntegrationUpdatedEvent.kt
        └── IntegrationDeletedEvent.kt
```

## Database Migrations (Flyway)

```mermaid
flowchart LR
    V1["V1__create_clients.sql"]
    V2["V2__create_client_integrations.sql"]
    V3["V3__create_integration_credentials.sql"]
    V4["V4__create_integration_event_log.sql"]

    V1 --> V2 --> V3 --> V4
```

## Implementation Phases

```mermaid
gantt
    title Integration Domain — Implementation Phases
    dateFormat  YYYY-MM-DD
    section Phase 1 — Foundation
    Client + ClientIntegration entities + migrations   :p1a, 2024-01-01, 5d
    CredentialService (AES-256-GCM encryption)         :p1b, after p1a, 3d
    IntegrationRegistry skeleton + factory             :p1c, after p1b, 4d
    Basic onboarding API (CRUD + test endpoint)        :p1d, after p1c, 5d

    section Phase 2 — SFTP
    Apache MINA SSHD connection pool                   :p2a, after p1d, 5d
    SftpConnector (inbound + outbound)                 :p2b, after p2a, 5d
    SftpInboundPoller + FileIngestionService           :p2c, after p2b, 4d
    SftpRecordWriter (pipeline outbound)               :p2d, after p2c, 3d

    section Phase 3 — Kafka + REST
    KafkaConnector (producer per client)               :p3a, after p2d, 4d
    RestConnector (Basic / Bearer / OAuth2)            :p3b, after p3a, 5d
    RestRecordWriter + retry policy                    :p3c, after p3b, 3d

    section Phase 4 — S3 + Hardening
    S3Connector (AWS SDK v2)                           :p4a, after p3c, 4d
    Health check endpoint + circuit breaker            :p4b, after p4a, 4d
    Credential rotation API                            :p4c, after p4b, 3d
    Integration event audit log                        :p4d, after p4c, 3d
```

## Key Dependencies to Add

```kotlin
// platform-integration/build.gradle.kts

dependencies {
    implementation(project(":platform-core"))
    implementation(project(":platform-common"))

    // SFTP — Apache MINA SSHD
    implementation("org.apache.sshd:sshd-sftp:2.12.1")
    implementation("org.apache.sshd:sshd-common:2.12.1")

    // S3 — AWS SDK v2
    implementation("software.amazon.awssdk:s3:2.25.0")

    // Encryption
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")

    // Spring Data JPA (already in Boot BOM)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
}
```

## Design Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| SFTP library | Apache MINA SSHD | Actively maintained, connection pooling, modern key exchange |
| Credential encryption | AES-256-GCM | Authenticated encryption, random IV per value |
| Registry events | Spring `ApplicationEventPublisher` | No new infra needed, stays in-process |
| Config storage | `JSONB` column | Flexible, queryable, avoids one-table-per-type sprawl |
| Circuit breaker | Resilience4j | Already standard in Spring Boot ecosystem |
| Credential rotation | Zero-downtime swap | Drain old connector, activate new before closing |
