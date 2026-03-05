---
id: pr-checklist
title: PR Checklist
sidebar_position: 3
---

# Pull Request Checklist

Before opening a PR, verify every item below:

## Code Quality

- [ ] `./gradlew build` passes (all modules, all tests)
- [ ] New code has tests — parsers use `DescribeSpec`, validators use `BehaviorSpec`
- [ ] Sensitive fields masked in all new log/error messages (`***` placeholder)
- [ ] No `runBlocking` added in production code paths outside `TransformService`
- [ ] No new `@Bean` factories unless justified — use `@Component`

## Documentation

| Change type | Files to update |
|-------------|----------------|
| New parser added | `AGENTS.md` §6, `README.md` supported formats |
| New writer added | `AGENTS.md` §6 |
| New correction/validation type | `AGENTS.md` §6 |
| New env variable | `AGENTS.md` §3, `.docker/env.example`, `.run/run-transform-app-local-config.xml` |
| New module added | `AGENTS.md` §2 + §5, `README.md` |
| Pipeline stage changed | `AGENTS.md` §5 architecture diagram |

- [ ] `AGENTS.md` updated if architecture or extension points changed
- [ ] `README.md` updated if supported formats or quick-start changed
- [ ] `.docker/env.example` updated if new env variables were added
- [ ] `.run/run-transform-app-local-config.xml` updated if new env variables were added

## PR Description

- [ ] Explains the **why**, not just the **what**
- [ ] Links related issues if applicable
- [ ] Notes any breaking changes

## Common Mistakes to Avoid

- **Do not** add `flyway-database-postgresql` — it does not exist in Boot 3.2.3 BOM
- **Do not** enable `bootJar` on `platform-pipeline` or `platform-scheduler`
- **Do not** use `type="SpringBootApplicationConfigurationType"` in `.run/` XML files — use `SpringBootApplicationRunConfiguration`
- **Do not** commit a filled `.env` file
