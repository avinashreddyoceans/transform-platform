# PR Best Practices — Team Guide

> A living reference for branch naming, commit messages, PR hygiene, and review quality.
> Raise a PR against this file if you think something should change.

---

## Table of contents

1. [Why this matters](#why-this-matters)
2. [Branch naming](#branch-naming)
3. [Commit messages](#commit-messages)
4. [PR size and focus](#pr-size-and-focus)
5. [PR template guide](#pr-template-guide)
6. [Review comment labels](#review-comment-labels)
7. [Team agreements](#team-agreements)
8. [Cheat sheet](#cheat-sheet)

---

## Why this matters

Every hour a PR sits unreviewed is an hour of blocked work. Every vague description is a back-and-forth thread that didn't need to happen. Every unlabelled review comment is a merge anxiety that didn't need to exist.

Bad PRs compound:
- Reviewers skim instead of read → bugs slip through
- Authors guess at feedback intent → wrong fixes, re-reviews
- No description → next developer can't understand why a change exists → tech debt

Good PRs are a form of documentation. They tell a story: what was broken, why, and how it was fixed. That story lives in your git history forever.

---

## Branch naming

### Convention

```
type/JIRA-TICKET/short-description
```

### Types

| Type | When to use |
|---|---|
| `feat` | New feature or capability |
| `fix` | Bug fix |
| `chore` | Dependency update, config change, tooling |
| `docs` | Documentation only |
| `refactor` | Code restructure with no behaviour change |
| `test` | Adding or fixing tests only |

### Examples

```bash
# ✅ Good
feat/OBS-123/local-observability-stack
fix/OBS-456/elasticsearch-pipeline-drop
chore/OBS-789/pin-otel-collector-version
docs/OBS-101/update-local-dev-readme
refactor/OBS-202/extract-batch-processor-config

# ❌ Bad
avi-working-branch
test123
wip
observability-stuff
fix-the-thing
```

### Rules

- Lowercase only, hyphens between words
- Ticket number is mandatory — links the branch to a trackable unit of work
- Description should be readable by someone who hasn't seen the Jira ticket
- Keep it under 60 characters total

---

## Commit messages

### Convention

```
type(scope): what it does
```

- **Present tense** — `add`, not `added`
- **Imperative mood** — `fix`, not `fixes`
- **One line** — under 72 characters for the subject
- **No period** at the end of the subject line
- Optionally add a blank line + longer body for context

### Examples

```bash
# ✅ Good
feat(observability): add otel collector docker compose stack
fix(elasticsearch): remove empty pipeline key causing bulk rejection
chore(deps): pin otel-collector-contrib to stable version
docs(readme): add local stack setup instructions
refactor(processor): extract batch config into separate file

# ❌ Bad
WIP
fixed stuff
asdf
observability changes
updated config
```

### Multi-line commit (when context matters)

```
fix(elasticsearch): replace deprecated mapping.mode with header

mapping.mode: none is silently ignored in otelcol-contrib v0.147.0.
The equivalent behaviour is now passed via the X-Elastic-Mapping-Mode
HTTP header on the exporter. Without this change every bulk request
falls back to ECS mode and routes to data streams that do not exist
in this local stack, causing resource_not_found_exception on every
document.
```

---

## PR size and focus

### The rules

| Rule | Threshold |
|---|---|
| Lines of diff | Aim for ≤ 400 |
| Concerns per PR | Exactly 1 |
| Files changed | Use judgement — 50+ is a smell |

### One concern means one thing

If you find yourself writing "and also..." in the PR description, you probably have two PRs.

```
# ✅ One concern
Add local observability stack (Docker Compose + OTel config)

# ❌ Two concerns bundled
Add local observability stack AND refactor batch processor config AND
update Gradle wrapper version
```

### Why size matters

Reviewers read carefully up to about 400 lines. After that, attention drops and the review becomes a skim. A skim catches fewer bugs. Smaller PRs get faster, higher quality reviews — which means faster merges.

If a feature genuinely requires more than 400 lines:
1. Break it into a base PR (infrastructure, interfaces) and a follow-up PR (implementation)
2. Use stacked PRs — each one reviewable in isolation
3. At minimum, leave a comment in the PR explaining why the size was unavoidable

---

## PR template guide

Every section in the template exists for a reason. This is what reviewers actually look for in each one.

### Description — the most important section

The description is not a summary of the diff. The diff already shows *what* changed. The description explains *why*.

```markdown
## Description

**The "Why"**: Developers had no local environment to observe application
behaviour — no logs, no traces, no metrics. Debugging required deploying
to a shared environment and waiting.

**The "What"**: Adds a fully containerised observability stack (OTel
Collector, Jaeger, Prometheus, Grafana, Elasticsearch, Kibana) wired via
Docker Compose. Mirrors production observability infrastructure.

**Context**: Production runs a similar stack. This brings local dev to
parity so instrumentation issues are caught before deployment.
```

**Common mistakes:**
- `"Updated the config"` — says nothing about why
- Copy-pasting the Jira ticket title — adds no value
- Describing what the code does instead of what problem it solves

### Type of change — one tick only

Reviewers scan this before reading anything else to calibrate their expectations. Ticking multiple boxes usually means you have multiple PRs in one.

### Impact analysis — flag what needs attention

This section exists so reviewers don't have to hunt for side effects. Be honest. If you added a new environment variable and didn't tick that box, someone will find out in production.

### How to test — eliminate back-and-forth

A reviewer who can verify the change themselves will approve faster and with more confidence. Include:

```markdown
## How to Test

1. Select **Docker: Observability Stack** from the IntelliJ Run Configurations dropdown and click Run
2. Start the application via its Run Configuration
3. Generate traffic and verify:

   ```bash
   # Logs flowing into Elasticsearch
   curl http://localhost:9200/batch-gateway-logs/_count

   # Traces visible in Jaeger
   open http://localhost:16686

   # Metrics scrape targets UP
   open http://localhost:9090/targets
   ```
```

If the only test instructions are `"./gradlew test"` you haven't told the reviewer anything useful about whether the feature actually works.

### Rollback plan — think before you merge

For infra and config changes especially: what happens if this breaks production? Write it down before the merge, not after the incident.

---

## Review comment labels

This is the single highest-leverage practice for faster, less anxious reviews. Label every comment so the author knows immediately whether they are blocked or not.

### Labels

| Label | Meaning | Blocking? |
|---|---|---|
| `nit:` | Optional polish — style, naming preference | No |
| `question:` | Genuinely asking, not criticising | No |
| `suggest:` | Here's an alternative, your call | No |
| `blocker:` | Must be addressed before merge | Yes |
| `idea:` | Out of scope for this PR, worth a follow-up ticket | No |

### Examples

```
# ❌ Bad — author doesn't know if they're blocked
"This is wrong."
"Why did you do it this way?"
"I'd have used a different approach here."

# ✅ Good — author knows exactly what to do
nit: variable name `cfg` could be `collectorConfig` for clarity

question: is there a reason we're using HTTP here instead of gRPC?
Just want to understand the tradeoff.

suggest: consider extracting this into a separate method — easier
to test in isolation. Happy either way.

blocker: this will cause a NPE if the ES client returns null on
startup. Need a null check before line 42.

idea: we could add a Grafana provisioned dashboard for this in a
follow-up ticket — not needed for this PR to merge.
```

### As a reviewer, also say what you liked

A review that is 100% corrections trains authors to be defensive. One specific positive comment per PR — on something genuinely good — builds the kind of team where people actually want their code reviewed.

```
# Example
Nice — the elasticsearch-setup container being idempotent means we
don't have to manually clean up between stack restarts. Good call.
```

---

## Team agreements

These are the three things agreed on as a team. They apply from today. Raise a PR against this file to change them.

1. **All branches follow** `type/JIRA-ticket/short-description` — no exceptions
2. **Every PR must fill** the Description and How to Test sections before requesting review
3. **Every review comment gets a label** — `nit`, `question`, `suggest`, `blocker`, or `idea`

---

## Cheat sheet

Pin this in Slack or stick it on your desk.

```
┌─────────────────────────────────────────────────────────────────┐
│  PR BEST PRACTICES — QUICK REFERENCE                            │
├─────────────────────────────────────────────────────────────────┤
│  BRANCH     feat|fix|chore|docs|refactor / TICKET / description │
│                                                                 │
│  COMMIT     type(scope): what it does — present tense, 1 line  │
│                                                                 │
│  PR SIZE    one concern per PR · aim for ≤ 400 lines diff       │
│                                                                 │
│  TEMPLATE   Description + How to Test are non-negotiable        │
│                                                                 │
│  REVIEW     label every comment:                                │
│             nit · question · suggest · blocker · idea           │
└─────────────────────────────────────────────────────────────────┘
```

---

## Further reading

- [Conventional Commits specification](https://www.conventionalcommits.org/)
- [Google Engineering Practices — Code Review](https://google.github.io/eng-practices/review/)
- [How to Write a Git Commit Message — Chris Beams](https://cbea.ms/git-commit/)

---

*Last updated: March 2026 · Raise a PR to suggest changes*
