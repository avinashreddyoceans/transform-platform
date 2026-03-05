---
id: overview
title: Module Overview
sidebar_position: 1
---

# Module Overview

The platform is organized into four Gradle modules, each with a distinct responsibility.

| Module | Purpose |
|--------|---------|
| [`platform-common`](./platform-common) | Shared models, exceptions, utilities — no Spring dependencies |
| [`platform-core`](./platform-core) | Spec engine, parsers, validators, transformers, writers |
| [`platform-api`](./platform-api) | REST API — spec management, file upload, transform orchestration |
| `platform-pipeline` | Spring Batch jobs for bulk processing *(in progress)* |
| `platform-scheduler` | Quartz-based scheduling and delay engine *(in progress)* |

## Dependency Graph

```
platform-api
    └── platform-core
            └── platform-common

platform-pipeline
    └── platform-core

platform-scheduler
    └── platform-core
```

`platform-api` is the only runnable module (has `bootJar` enabled). `platform-pipeline` and `platform-scheduler` do not have a `main` class and must not have `bootJar` enabled.
