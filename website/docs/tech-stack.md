---
id: tech-stack
title: Tech Stack
sidebar_position: 7
---

# Tech Stack

## Technology Layers

```mermaid
graph TB
    subgraph api["API Layer"]
        SB["Spring Boot 3.2.3"]
        OA["SpringDoc OpenAPI 2.3.0"]
        JWT["JJWT 0.12.3"]
    end

    subgraph core["Core / Engine"]
        KT["Kotlin 1.9.22"]
        CF["Kotlin Coroutines + Flow 1.7.3"]
        LOG["kotlin-logging 3.0.5"]
    end

    subgraph infra["Infrastructure"]
        KF["Spring Kafka"]
        PG["PostgreSQL + Flyway"]
    end

    subgraph build["Build & Test"]
        GR["Gradle 8.6 (Kotlin DSL)"]
        KO["Kotest 5.8.1"]
        MK["MockK 1.13.9"]
        JDK["JDK 21"]
    end

    api --> core
    core --> infra
    build -.->|tests| core

    style api fill:#dbeafe,stroke:#2563eb
    style core fill:#dcfce7,stroke:#16a34a
    style infra fill:#fef9c3,stroke:#ca8a04
    style build fill:#f3f4f6,stroke:#6b7280
```

## Version Table

| Concern | Choice | Version |
|---------|--------|---------|
| Language | Kotlin | 1.9.22 |
| Runtime | JDK | 21 |
| Framework | Spring Boot | 3.2.3 |
| Build | Gradle (Kotlin DSL) | 8.6 |
| Streaming | Kotlin Coroutines / Flow | 1.7.3 |
| Messaging | Spring Kafka | *(managed by Boot BOM)* |
| Database | PostgreSQL + Flyway | *(managed by Boot BOM)* |
| API Docs | SpringDoc OpenAPI | 2.3.0 |
| Auth | JJWT | 0.12.3 |
| Testing | Kotest | 5.8.1 |
| Mocking | MockK | 1.13.9 |
| Logging | kotlin-logging (mu) | 3.0.5 |

## Dependency Version Policy

```mermaid
flowchart TD
    Q{Is the dependency\nmanaged by Spring Boot BOM?}
    Q -->|Yes| USE[Use BOM version\nno explicit override needed]
    Q -->|No| NEED{Do you need\na specific version?}
    NEED -->|Yes| OVER[Override in build.gradle.kts\nand document the reason]
    NEED -->|No| ADD[Add without version\nlet BOM resolve]

    style USE fill:#dcfce7,stroke:#16a34a
    style OVER fill:#fef9c3,stroke:#ca8a04
```

:::danger Common Pitfall
Never add `flyway-database-postgresql` as a dependency. It does not exist in Flyway 9.x (managed by Boot 3.2.3 BOM). `flyway-core` is sufficient.
:::

## Why Kotlin Coroutines + Flow?

```mermaid
flowchart LR
    subgraph bad["Without Flow (bad)"]
        direction TB
        B1[Load entire file into List] --> B2[10 GB file = 10 GB RAM]
        B2 --> B3[OOM for large files]
        style bad fill:#fee2e2,stroke:#ef4444
    end

    subgraph good["With Flow (platform approach)"]
        direction TB
        G1[Emit one ParsedRecord at a time] --> G2[10 GB file = constant RAM]
        G2 --> G3[Backpressure built-in]
        style good fill:#dcfce7,stroke:#16a34a
    end
```
