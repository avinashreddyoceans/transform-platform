# Transform Platform

Enterprise-grade, spec-driven file ↔ event transformation engine with a multi-agent AI assistant.

---

## What it does

Transform Platform ingests files (CSV, Fixed-Width, XML, ISO 20022, NACHA, SWIFT MT, and more), validates and corrects each record against a declarative `FileSpec`, then publishes results to Kafka. No code changes are needed to support a new file layout — register a spec, upload a file.

A built-in multi-agent AI assistant helps you set up pipelines, build file specs, and analyse execution metrics through natural language.

---

## Architecture

```
platform-ui      (React + Vite — served from :8080/ui/ in production, :5173/ui/ in dev)
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
- Docker Desktop (must be running before any `docker compose` command)
- An Anthropic API key (required for the AI assistant)

---

## Full startup guide

Follow these steps **in order**. Each service depends on the one above it.

### Step 1 — Start Docker Desktop

Open Docker Desktop and wait until it shows "Docker Desktop is running" in the menu bar. All infrastructure runs in Docker.

### Step 2 — Start infrastructure (Postgres + Kafka)

```bash
cd .docker
docker compose up -d postgres kafka zookeeper
```

Wait ~10 seconds for Postgres to accept connections before starting the API.

> **Note:** If `docker compose up -d` with no service names fails with "no service selected", specify services explicitly as shown above.

### Step 3 — Configure environment variables

Copy the example file and fill in secrets:

```bash
cp .docker/env.example .docker/.env
```

Minimum required values in `.docker/.env`:

```
DB_USER=transform_user
DB_PASS=transform_pass
KAFKA_BROKERS=localhost:9092
JWT_SECRET=<any-long-random-string>
ANTHROPIC_API_KEY=sk-ant-...
```

The Spring Boot app also reads these as JVM env vars — they are wired in `.run/run-transform-app-local-config.xml` for IntelliJ. For the terminal, export them or prefix the `bootRun` command:

```bash
DB_USER=transform_user DB_PASS=transform_pass KAFKA_BROKERS=localhost:9092 \
  JWT_SECRET=local-dev-secret ANTHROPIC_API_KEY=sk-ant-... \
  ./gradlew :platform-api:bootRun
```

### Step 4 — Start the Spring Boot API

```bash
# From the repo root
./gradlew :platform-api:bootRun
```

The API starts on **http://localhost:8080**.
The built React UI (production bundle) is served at **http://localhost:8080/ui/**.

> **Port conflict?** Kill whatever is on 8080 first:
> ```bash
> lsof -ti :8080 | xargs kill -9
> ```

### Step 5 — Start the AI chatbot

The chatbot has its own Python virtualenv inside `platform-chatbot/.venv`.

```bash
cd platform-chatbot

# First-time setup only
python3 -m venv .venv
.venv/bin/pip install -e .

# Copy and fill in chatbot env
cp .env.example .env          # set ANTHROPIC_API_KEY and TRANSFORM_API_URL

# Start the server
.venv/bin/uvicorn src.main:app --reload --port 8000
```

Health check: **http://localhost:8000/health**
Also reachable via the Spring Boot proxy: **http://localhost:8080/chatbot/health**

> **On subsequent runs** you only need:
> ```bash
> cd platform-chatbot && .venv/bin/uvicorn src.main:app --reload --port 8000
> ```

### Step 6 — (Optional) Start the UI in dev mode

For hot-reload during frontend development:

```bash
cd platform-ui && npm install   # first time only
npm run dev
```

Dev UI is at **http://localhost:5173/ui/**.
Vite proxies `/api` → `:8080` and `/chatbot` → `:8000` automatically.

---

## Using the UI

Open **http://localhost:8080/ui/** (production) or **http://localhost:5173/ui/** (dev) in your browser.

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
- **Named wizard steps** — during the Onboarding wizard a horizontal step indicator appears at the top: `Use Case → File Spec → Integration → Profile → Enable`, with filled circles for completed steps and the active step highlighted
- **Structured data cards** — when an agent retrieves data, it renders inline below the text:
  - *Profile list* — table of name, client, status
  - *File spec list* — table of name, format badge, field count
  - *Execution list* — table of status badge, profile, records, started date
  - *Window list* — table of status, profile, opened/closed dates
  - *Metrics summary* — 2×2 KPI grid (success rate, total executions, avg duration, records/day) with colour-coded success rate
  - *Error summary* — failed execution count + ranked error category list
  - *Resource created* — green confirmation card with resource name and ID shown after a create tool call
- **Tool badges** — small chips under each AI response show which API tools were called
- **New conversation** — click the refresh icon to reset context and start a fresh session

---

## Development mode (hot-reload)

Run each in a separate terminal:

```bash
# Terminal 1 — Spring Boot API (with env vars)
DB_USER=transform_user DB_PASS=transform_pass KAFKA_BROKERS=localhost:9092 \
  JWT_SECRET=local-dev-secret ANTHROPIC_API_KEY=sk-ant-... \
  ./gradlew :platform-api:bootRun

# Terminal 2 — AI chatbot
cd platform-chatbot && .venv/bin/uvicorn src.main:app --reload --port 8000

# Terminal 3 — React UI with HMR
cd platform-ui && npm run dev
```

Access the UI at **http://localhost:5173/ui/** in dev mode.

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
docker compose -f .docker/docker-compose.yml up -d postgres kafka zookeeper
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
| `TRANSFORM_API_URL` | `http://localhost:8080` | Spring Boot base URL |
| `AI_MODEL` | `claude-sonnet-4-6` | Claude model ID |
| `AI_MAX_TOKENS` | `2048` | Max tokens per response |
| `SESSION_TTL_MINUTES` | `120` | Session inactivity timeout |

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| **`Cannot connect to Docker daemon`** | Open Docker Desktop and wait for it to fully start before running `docker compose` |
| **`no service selected` from docker compose** | Specify services explicitly: `docker compose up -d postgres kafka zookeeper` |
| **Spring Boot fails with `Connection refused` to Postgres** | Postgres container isn't up yet — wait 10s after `docker compose up` and retry |
| **Port 8080 already in use** | `lsof -ti :8080 \| xargs kill -9` |
| **`command not found: uvicorn`** | Use the venv directly: `cd platform-chatbot && .venv/bin/uvicorn src.main:app --reload --port 8000` |
| **`command not found: python`** | Use `python3` for venv creation: `python3 -m venv .venv` |
| **Blank screen at `/ui/`** | Run `npm run build` in `platform-ui/`, restart Spring Boot |
| **Chatbot returns 500** | Python service on port 8000 is not running, or `ANTHROPIC_API_KEY` is not set |
| **Chat panel shows "Something went wrong"** | Check chatbot terminal output for Python errors |
| **`BadPaddingException` on startup** | Integration credentials encrypted with a different key — set `ENCRYPTION_ENABLED=false` or recreate the DB |
| **Profile creation fails with 400** | `windowConfig` requires `openTrigger` and `closeTrigger` each with a `"type"` field — ask the AI to create it instead |

See `SKILL.md` for the full developer runbook.
