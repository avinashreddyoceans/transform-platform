---
id: pr-checklist
title: PR Checklist
sidebar_position: 4
---

# Pull Request Checklist

## PR Lifecycle

```mermaid
flowchart LR
    A([Write code]) --> B[Run tests\n./gradlew test]
    B --> C{All passing?}
    C -->|No| A
    C -->|Yes| D[Update docs\nAGENTS.md / README]
    D --> E[Open PR\nexplain the why]
    E --> F[CI runs\nBuild + Kotest]
    F --> G{CI green?}
    G -->|No| A
    G -->|Yes| H([Ready for review])

    style A fill:#dbeafe,stroke:#2563eb
    style H fill:#dcfce7,stroke:#16a34a
    style C fill:#fef9c3,stroke:#ca8a04
    style G fill:#fef9c3,stroke:#ca8a04
```

## Code Quality

- [ ] `./gradlew build` passes (all modules, all tests)
- [ ] New code has tests — parsers use `DescribeSpec`, validators use `BehaviorSpec`
- [ ] Sensitive fields masked in all new log/error messages (`***` placeholder)
- [ ] No `runBlocking` added in production code paths outside `TransformService`
- [ ] No new `@Bean` factories unless justified — use `@Component`

## Documentation to Update

```mermaid
flowchart TD
    CHANGE{Type of change}

    CHANGE -->|New parser| PA["AGENTS.md §6\nREADME.md supported formats"]
    CHANGE -->|New writer| WA["AGENTS.md §6"]
    CHANGE -->|New correction or validation type| CA["AGENTS.md §6"]
    CHANGE -->|New env variable| EA["AGENTS.md §3\n.docker/env.example\n.run/run-transform-app-local-config.xml"]
    CHANGE -->|New module| MA["AGENTS.md §2 + §5\nREADME.md"]
    CHANGE -->|Pipeline stage changed| PIA["AGENTS.md §5 architecture diagram"]
```

- [ ] `AGENTS.md` updated if architecture or extension points changed
- [ ] `README.md` updated if supported formats or quick-start changed
- [ ] `.docker/env.example` updated if new env variables were added
- [ ] `.run/run-transform-app-local-config.xml` updated if new env variables were added

## PR Description

- [ ] Explains the **why**, not just the **what**
- [ ] Links related issues if applicable
- [ ] Notes any breaking changes

## Common Mistakes to Avoid

| Do not                                                      | Reason                                       |
|-------------------------------------------------------------|----------------------------------------------|
| Add `flyway-database-postgresql`                            | Does not exist in Boot 3.2.3 BOM             |
| Enable `bootJar` on pipeline/scheduler modules              | No main class — build fails                  |
| Use `SpringBootApplicationConfigurationType` in `.run/` XML | Wrong type ID — IntelliJ silently ignores it |
| Commit a filled `.env` file                                 | Gitignored for a reason                      |
