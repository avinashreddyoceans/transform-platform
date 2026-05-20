# Transform Platform

This file is intentionally thin. The project's knowledge base is **`AGENTS.md`** —
the single, cross-tool source of truth that Claude Code, GitHub Copilot, and other
agents all read. The line below imports it so Claude Code picks it up automatically:

@AGENTS.md

## Where to look

- **`AGENTS.md`** — architecture, conventions, extension points (imported above).
- **`SKILL.md`** — day-to-day developer runbook (setup, running, tests, API,
  troubleshooting). Read it on demand; it is not auto-imported here to keep
  context lean.
- **`.claude/skills/`** — project skills: step-by-step procedures Claude invokes
  automatically when relevant (e.g. `db-schema-change`).
