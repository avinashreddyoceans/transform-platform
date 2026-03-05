---
id: overview
title: Module Overview
sidebar_position: 1
---

# Module Overview

The platform is split into four Gradle modules, each with a single responsibility.

## Module Dependency Graph

```mermaid
graph TD
    API["🌐 platform-api\nSpring Boot REST API\n(only runnable module)"]
    CORE["⚙️ platform-core\nSpec engine · Parsers\nValidators · Writers"]
    COMMON["📦 platform-common\nShared models · Exceptions\n(no Spring dependency)"]
    PIPELINE["🔄 platform-pipeline\nSpring Batch bulk jobs\n⏳ in progress"]
    SCHEDULER["🕐 platform-scheduler\nQuartz scheduling\n⏳ in progress"]

    API --> CORE
    PIPELINE --> CORE
    SCHEDULER --> CORE
    CORE --> COMMON

    style API fill:#dbeafe,stroke:#2563eb
    style CORE fill:#dcfce7,stroke:#16a34a
    style COMMON fill:#f3f4f6,stroke:#6b7280
    style PIPELINE fill:#fef9c3,stroke:#ca8a04
    style SCHEDULER fill:#fef9c3,stroke:#ca8a04
```

## Module Summary

| Module | Purpose | Runnable? |
|--------|---------|-----------|
| [`platform-common`](./platform-common) | Shared models, exceptions, utilities — no Spring | No |
| [`platform-core`](./platform-core) | Spec engine, parsers, validators, transformers, writers | No |
| [`platform-api`](./platform-api) | REST API — spec management, file upload, transform orchestration | **Yes** |
| `platform-pipeline` | Spring Batch jobs for bulk processing *(in progress)* | No |
| `platform-scheduler` | Quartz-based scheduling and delay engine *(in progress)* | No |

:::warning
Never enable `bootJar` on `platform-pipeline` or `platform-scheduler` — they have no `main` class and the build will fail.
:::
