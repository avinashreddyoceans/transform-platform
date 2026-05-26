# Transform Platform

Enterprise-grade, spec-driven file ↔ event transformation engine with a multi-agent AI assistant.

---

## What it does

Transform Platform ingests files (CSV, Fixed-Width, XML, ISO 20022, NACHA, SWIFT MT, and more), validates and corrects each record against a declarative `FileSpec`, then publishes results to Kafka. No code changes are needed to support a new file layout — register a spec, upload a file.

A built-in multi-agent AI assistant helps you set up pipelines, build file specs, and analyse execution metrics through natural language.

---

## Architecture

```
platform-ui      (React + Vite — served from :8080/ui/ in production, :5173 in dev)
      |
platform-api     (Spring Boot 3.2.3 / Kotlin, port 8080)
      |                               |
platform-chatbot (FastAPI + LangGraph, port 8000)        PostgreSQL + Kafka
```

| Module | Purpose |
|--------|---------|
| `platform-api` | REST API, Spring Boot entry point, serves the built React UI at `/ui/` |
| `platform-ui` | React SPA — Profiles, Windows, Executions, Dashboard, AI chat panel |
| `platform-chatbot` | Python LangGraph multi-agent chatbot (Supervisor + 4 specialist agents) |
| `platform-core` | Parsers, validation/correction engine, Kafka writer |
| `platform-common` | Shared models, no framework dependencies |
| `platform-pipeline` | Spring Batch jobs (future) |
| `platform-scheduler` | Quartz scheduler (future) |

---

## Prerequisites

- Java 21+
- Node 18+ / npm
- Python 3.12+
- Docker (for PostgreSQL + Kafka)
- An Anthropic API key (required for the AI assistant)

---

## Quick start

### 1. Start infrastructure

```bash
cd .docker
docker compose up -d          # starts Postgres + Kafka + Zookeeper
```

### 2. Configure environment

Copy `.docker/env.example` to `.docker/.env` and fill in:

```
DB_USER=transform_user
DB_PASS=transform_pass
KAFKA_BROKERS=localhost:9092
JWT_SECRET=<any-long-random-string>
ANTHROPIC_API_KEY=sk-ant-...
```

### 3. Start the API

```bash
./gradlew :platform-api:bootRun
```

The API starts on **http://localhost:8080**.
The React UI is served at **http://localhost:8080/ui/**.

### 4. Start the AI chatbot

```bash
cd platform-chatbot
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -e .
ANTHROPIC_API_KEY=sk-ant-... uvicorn src.main:app --reload --port 8000
```

Health check: **http://localhost:8080/chatbot/health**

The chatbot is also accessible through the Spring Boot reverse proxy at `/chatbot/`.

---

## Using the UI

Open **http://localhost:8080/ui/** in your browser.

| Page | What it shows |
|------|--------------|
| **Dashboard** | Profile summary and recent activity |
| **Profiles** | Create, edit, and enable processing profiles |
| **Windows** | Scheduling windows — open/closed state |
| **Executions** | Job run history with record counts and status |

### AI Assistant

Click the **AI Assistant** button in the bottom-right corner. The assistant automatically routes your request to the right specialist agent:

| Agent | Activated by | Capabilities |
|-------|-------------|--------------|
| **Onboarding** | "Help me set up my first pipeline", "onboard me", "walk me through" | Guided 5-step wizard — creates a file spec, integration, and profile end-to-end |
| **Flow Builder** | "Build a CSV spec", "add a validation rule", "create a profile" | Builds `FileSpec` and `Profile` objects conversationally |
| **Insights** | "Show me failures this week", "success rate", "metrics", "how many records" | Aggregates execution data, surfaces error trends and throughput |
| **General** | Everything else | Lists and fetches any platform resource |

**Features in the chat panel:**
- **Agent badge** — coloured chip shows which agent is active (Emerald = Onboarding, Violet = Flow Builder, Amber = Insights, Indigo = General)
- **Wizard progress bar** — appears at the top during the Onboarding wizard, e.g. "Step 2 / 5"
- **Tool badges** — small chips under each AI response show which API tools were called
- **New conversation** — click the refresh icon to reset context and start a fresh session

---

## Development mode (hot-reload)

```bash
# Terminal 1 — API
./gradlew :platform-api:bootRun

# Terminal 2 — Chatbot
cd platform-chatbot && source .venv/bin/activate
ANTHROPIC_API_KEY=sk-ant-... uvicorn src.main:app --reload --port 8000

# Terminal 3 — UI (Vite HMR)
cd platform-ui && npm run dev
```

Access the UI at **http://localhost:5173** in dev mode. Vite proxies `/api` to `:8080` and `/chatbot` to `:8000`.

---

## Building for production

```bash
cd platform-ui && npm run build   # outputs to platform-api/src/main/resources/static/ui/
./gradlew :platform-api:bootJar   # creates platform-api/build/libs/platform-api-*.jar
java -jar platform-api/build/libs/platform-api-*.jar
```

The fat JAR includes the React UI. No separate frontend server needed.

---

## Docker Compose (full stack)

```bash
cp .docker/env.example .docker/.env   # fill in ANTHROPIC_API_KEY and secrets
docker compose -f .docker/docker-compose.yml --profile core up -d
```

| Service | URL |
|---------|-----|
| Platform UI + API | http://localhost:8080/ui/ |
| Chatbot (via proxy) | http://localhost:8080/chatbot/health |
| Kafka UI | http://localhost:8085 |
| PostgreSQL | localhost:5432 |

---

## REST API

OpenAPI docs: **http://localhost:8080/swagger-ui.html**

Key endpoints:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/specs` | List file specs |
| `POST` | `/api/v1/specs` | Create file spec |
| `GET` | `/api/v1/integrations` | List integrations (SFTP, S3, FTP, Kafka) |
| `POST` | `/api/v1/integrations` | Create integration |
| `GET` | `/api/profiles` | List processing profiles |
| `POST` | `/api/profiles` | Create profile |
| `POST` | `/api/profiles/{id}/enable` | Enable a profile |
| `GET` | `/api/executions` | List all executions |
| `POST` | `/chatbot/sessions` | Create AI chat session |
| `POST` | `/chatbot/sessions/{id}/chat` | Send message to AI |
| `DELETE` | `/chatbot/sessions/{id}` | Clear a session |

---

## Creating a FileSpec

```json
POST /api/v1/specs
{
  "name": "Bank Transactions CSV",
  "format": "CSV",
  "hasHeader": true,
  "delimiter": ",",
  "fields": [
    { "name": "accountNumber", "type": "STRING",  "columnName": "account_number", "sensitive": true },
    { "name": "amount",        "type": "DECIMAL", "columnName": "amount" },
    { "name": "transactionDate","type": "DATE",   "columnName": "date", "format": "yyyy-MM-dd" }
  ],
  "correctionRules": [
    { "ruleId": "trim-amount", "field": "amount", "correctionType": "TRIM" }
  ],
  "validationRules": [
    { "ruleId": "amount-positive", "field": "amount", "ruleType": "MIN_VALUE", "value": "0",
      "message": "Amount must be positive", "severity": "ERROR" }
  ]
}
```

Or just ask the AI: *"Build a CSV file spec for bank transactions with account number, amount, and date fields."*

---

## Chatbot architecture

```
POST /chatbot/sessions/{id}/chat
            |
      SupervisorNode  ← LLM intent classifier (max_tokens=20)
            |
  ┌──────────┬──────────┬──────────┐
  Onboarding  FlowBuilder  Insights  General
  Agent       Agent        Agent     Agent
            |
  httpx tools → Spring Boot REST API (:8080)
```

Sessions are in-memory with a 2-hour TTL. After a server restart, create a new session.

**Chatbot environment variables:**

| Variable | Default | Description |
|----------|---------|-------------|
| `ANTHROPIC_API_KEY` | (required) | Anthropic API key |
| `AI_MODEL` | `claude-sonnet-4-6` | Claude model ID |
| `AI_MAX_TOKENS` | `2048` | Max tokens per response |
| `TRANSFORM_API_URL` | `http://localhost:8080` | Spring Boot base URL |

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| **Blank screen at `/ui/`** | Run `npm run build` in `platform-ui/`, restart Spring Boot |
| **Chatbot returns 500** | Python service on port 8000 is not running, or `ANTHROPIC_API_KEY` is not set |
| **Chat panel shows "Something went wrong"** | Check `/tmp/chatbot.log` for Python errors |
| **`BadPaddingException` on startup** | Integration credentials encrypted with a different key — set `ENCRYPTION_ENABLED=false` or recreate the DB |
| **Port 8080 already in use** | `lsof -i :8080 \| grep LISTEN` to find the PID, then `kill <PID>` |
| **Profile creation fails with 400** | `windowConfig` requires `openTrigger` and `closeTrigger` each with a `"type"` field — ask the AI to create it instead |

See `SKILL.md` for the full developer runbook.
