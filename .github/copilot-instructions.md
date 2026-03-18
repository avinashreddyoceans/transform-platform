# GitHub Copilot Instructions — Transform Platform

This file configures GitHub Copilot's behaviour across the repository,
including commit message generation in IntelliJ and VS Code.

---

## Commit messages

When generating a commit message, always follow this exact format:

```
type(scope): short imperative description
```

### Rules

- **Present tense, imperative mood** — `add`, `fix`, `remove`, not `added`, `fixes`, `removed`
- **Subject line under 72 characters** — no exceptions
- **No capital letter** at the start of the description
- **No period** at the end of the subject line
- **One logical change per commit** — do not bundle unrelated changes
- If the change needs context, add a **blank line** after the subject, then a
  prose body explaining *why* (not *what* — the diff already shows what)

### Type — pick exactly one

| Type       | When to use                                                    |
|------------|----------------------------------------------------------------|
| `feat`     | New feature or new capability visible to users or consumers    |
| `fix`      | Bug fix — corrects incorrect behaviour                         |
| `chore`    | Dependency update, config change, build tooling, CI pipeline   |
| `docs`     | Documentation only — no production code changed               |
| `refactor` | Code restructure with no observable behaviour change           |
| `test`     | Adding or fixing tests only — no production code changed       |
| `hotfix`   | Critical production fix branched from `main`                   |

### Scope — pick the most specific area changed

Use the module, layer, or subsystem name in lowercase. Common scopes for this project:

| Scope           | What it covers                                           |
|-----------------|----------------------------------------------------------|
| `api`           | REST controllers, DTOs, `TransformService`               |
| `pipeline`      | `TransformationPipeline`, orchestration logic            |
| `parser`        | Any file parser (`CsvParser`, `JsonParser`, etc.)        |
| `validator`     | `ValidationEngine`, field validators                     |
| `transformer`   | `CorrectionEngine`, field transformers                   |
| `writer`        | `RecordWriter` and format-specific implementations       |
| `spec`          | `FileSpec`, `ParsedRecord`, enums, `ParserRegistry`      |
| `observability` | OTel Collector, Prometheus, Grafana, Jaeger, Kibana      |
| `elasticsearch` | Elasticsearch client, index templates, bulk requests     |
| `camel`         | Apache Camel routes, processors, route policies          |
| `scheduler`     | `platform-scheduler` module, job scheduling              |
| `deps`          | Gradle dependencies, version bumps                       |
| `config`        | `application.yml`, Spring configuration classes          |
| `docker`        | Docker Compose, Dockerfiles, container configs           |
| `ci`            | GitHub Actions workflows                                 |
| `startup`       | Application bootstrap, `@PostConstruct`, init logic      |

If the change genuinely spans multiple scopes with no clear primary, omit the
scope: `fix: correct null check on ES client initialisation`.

### Good examples

```
feat(parser): add CSV parser with configurable delimiter support
fix(elasticsearch): remove empty pipeline key causing bulk rejection
chore(deps): pin otel-collector-contrib to v0.147.0
docs(readme): add local stack setup instructions
refactor(pipeline): extract batch config into dedicated data class
test(validator): add boundary tests for numeric range validator
feat(observability): provision grafana datasources and dashboards
fix(camel): handle null exchange body in transform route processor
chore(ci): cache gradle wrapper to speed up build job
hotfix(startup): add null check on ES client before first request
```

### Bad examples — never generate these

```
WIP
fixed stuff
update
observability changes
misc fixes
added new feature
Updated Config
fix the thing.
```

### Multi-line commit — use when context is non-obvious

```
fix(elasticsearch): replace deprecated mapping.mode with header

mapping.mode: none is silently ignored in otelcol-contrib v0.147.0.
The equivalent behaviour is now passed via the X-Elastic-Mapping-Mode
HTTP header on the exporter. Without this change every bulk request
falls back to ECS mode and routes to data streams that do not exist
in this local stack, causing resource_not_found_exception on every
document.
```

The body explains **why** the change was necessary, not what lines changed.

---

## Pull request titles

PR titles follow the same `type(scope): description` format as commit messages.
The squash-merge commit on `develop` or `main` will use this title.

```
feat(observability): add local observability stack [OBS-123]
fix(elasticsearch): remove empty pipeline key [OBS-456]
```

Always append the Jira ticket in square brackets at the end.

---

## Code style reminders

- Kotlin: data classes for all models, `suspend fun` for I/O, `Flow<T>` for streams
- Logging: `private val log = KotlinLogging.logger {}` at file level
- Never throw inside a `Flow { }` block — add `ParseError` to the record instead
- Always check `fieldSpec.sensitive` before logging a field value
