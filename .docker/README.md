# Local Development Stack

Everything the app needs to run locally is in this directory. One command starts all infrastructure:

```bash
docker compose -f .docker/docker-compose.yml up -d
```

To stop everything and keep data volumes:

```bash
docker compose -f .docker/docker-compose.yml down
```

To stop and **wipe all data** (fresh start):

```bash
docker compose -f .docker/docker-compose.yml down -v
```

---

## Service Map

| Service | URL | Credentials |
|---|---|---|
| **App** | http://localhost:8080 | none (local dev) |
| **Swagger UI** | http://localhost:8080/swagger-ui | none |
| **Actuator** | http://localhost:8080/actuator | none |
| **Kafka UI** | http://localhost:8090 | none |
| **Prometheus** | http://localhost:9090 | none |
| **Grafana** | http://localhost:3001 | `admin` / `admin` |
| **Jaeger** | http://localhost:16686 | none |
| **Kibana** | http://localhost:5601 | none |
| **Elasticsearch** | http://localhost:9200 | none |
| **PostgreSQL** | `localhost:5432` | see below |
| **Docs (Docusaurus)** | http://localhost:3000 | none — port 3001 reserved for Grafana |

> **Port note:** Grafana runs on host port `3001` (container port 3000 mapped to host 3001).
> This avoids a conflict with the Docusaurus dev server which also uses port 3000.

---

## 1. Application (port 8080)

Start the app after Docker dependencies are healthy:

```bash
# Gradle
./gradlew :platform-api:bootRun

# Or use the IntelliJ run config: "TransformPlatformApi - Local"
```

Verify it is up:

```
GET http://localhost:8080/actuator/health
```

Expected response:

```json
{ "status": "UP" }
```

---

## 2. Swagger UI — Interactive API Explorer

**URL:** http://localhost:8080/swagger-ui

No login required in local dev. The Swagger UI lists every endpoint with its request/response schema. You can send test requests directly from the browser.

The raw OpenAPI spec is available as:
- JSON: http://localhost:8080/api-docs
- YAML: http://localhost:8080/api-docs.yaml

Use the YAML to generate a client in any language with [openapi-generator](https://openapi-generator.tech).

---

## 3. Actuator — Health & Metrics

**Base URL:** http://localhost:8080/actuator

**No credentials required** in the `local` and `dev-text` Spring profiles. In staging/production the actuator endpoints would be secured or restricted to an internal network.

### Key endpoints

| Endpoint | What it shows |
|---|---|
| `/actuator/health` | Full health report — DB, Kafka, disk, liveness, readiness |
| `/actuator/health/liveness` | Is the app process alive? (used by Kubernetes) |
| `/actuator/health/readiness` | Is the app ready to serve traffic? |
| `/actuator/health/db` | PostgreSQL connectivity |
| `/actuator/health/kafka` | Kafka connectivity |
| `/actuator/info` | App name, version, build info |
| `/actuator/metrics` | Index of all registered meters |
| `/actuator/metrics/{name}` | Single meter value — e.g. `transform.records.processed` |
| `/actuator/prometheus` | All metrics in Prometheus text format (scraped every 15s) |
| `/actuator/traces` | Recent distributed trace spans (before export to Jaeger) |

### Custom business metrics

These are registered in `TransformMetrics.kt`:

```
transform.records.processed      — total records published to Kafka
transform.records.failed         — records that failed validation or parsing
transform.records.corrected      — records that were auto-corrected before processing
transform.records.warnings       — records processed with warnings
transform.file.processing.time   — file processing duration histogram (p50/p95/p99)
transform.kafka.publish.time     — Kafka publish duration histogram
transform.pipeline.active.jobs   — gauge of concurrently running pipeline jobs
```

To query a specific metric with tag filtering:

```
GET /actuator/metrics/transform.records.processed?tag=specId:your-spec-id
```

---

## 4. Kafka (port 9092) + Kafka UI (port 8090)

### Kafka UI

**URL:** http://localhost:8090

No credentials required. The UI shows:
- All topics and their partition/offset state
- Consumer groups and lag
- Individual messages (browse, search, produce)
- Broker configuration

### Connecting a Kafka client directly

| Setting | Value |
|---|---|
| Bootstrap servers | `localhost:9092` |
| Security protocol | `PLAINTEXT` (no auth in local dev) |

### Useful topics (auto-created on first publish)

| Topic | Purpose |
|---|---|
| `transform.records.local` | Default topic for local dev transforms |
| `transform.ach.records.local` | ACH/NACHA record events |

### Produce a test message from the CLI

```bash
docker exec -it transform-kafka \
  kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic transform.records.local
```

Type a message and press Enter. Ctrl+C to exit.

### Consume messages from the CLI

```bash
docker exec -it transform-kafka \
  kafka-console-consumer \
    --bootstrap-server localhost:9092 \
    --topic transform.records.local \
    --from-beginning
```

---

## 5. PostgreSQL (port 5432)

### Connection details

| Setting | Value |
|---|---|
| Host | `localhost` |
| Port | `5432` |
| Database | `transform_platform` |
| Username | `transform_user` |
| Password | `transform_pass` |
| JDBC URL | `jdbc:postgresql://localhost:5432/transform_platform` |

### Connect with psql (CLI)

```bash
# From your machine (requires psql installed)
psql -h localhost -p 5432 -U transform_user -d transform_platform

# From inside the container (no local psql needed)
docker exec -it transform-postgres \
  psql -U transform_user -d transform_platform
```

### Connect with a GUI client

Any of these work — use the connection details above:

- **TablePlus** (macOS/Windows) — free tier available, recommended
- **DBeaver** (all platforms, free) — File → New Database Connection → PostgreSQL
- **pgAdmin 4** (all platforms, free) — see section 5a below
- **DataGrip** (JetBrains, paid) — if you have a license

### 5a. pgAdmin 4 (browser-based DB admin)

pgAdmin is not in the Docker Compose stack by default, but you can add it temporarily without touching `docker-compose.yml`:

```bash
docker run -d \
  --name pgadmin \
  --network transform-platform_default \
  -e PGADMIN_DEFAULT_EMAIL=admin@local.dev \
  -e PGADMIN_DEFAULT_PASSWORD=admin \
  -p 5050:80 \
  dpage/pgadmin4
```

Then open http://localhost:5050 and log in with `admin@local.dev` / `admin`.

Add a server:
- **Name:** Transform Platform Local
- **Host:** `transform-postgres` (Docker container name — works because both containers are on the same network)
- **Port:** `5432`
- **Username:** `transform_user`
- **Password:** `transform_pass`

> To get the network name if it differs: `docker network ls | grep transform`

### Useful queries

```sql
-- List all tables (Flyway managed)
SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;

-- Check Flyway migration history
SELECT * FROM flyway_schema_history ORDER BY installed_rank;

-- Count specs by format
SELECT format, COUNT(*) FROM file_specs GROUP BY format ORDER BY format;
```

---

## 6. Prometheus (port 9090)

**URL:** http://localhost:9090

No credentials required. Prometheus scrapes the app's `/actuator/prometheus` endpoint every 15 seconds (configured in `prometheus.yml`).

### Useful queries (paste into the Expression bar)

```promql
# All transform records processed, split by spec and status
transform_records_processed_total

# Processing duration p99 over the last 5 minutes
histogram_quantile(0.99,
  rate(transform_file_processing_duration_seconds_bucket[5m])
)

# Failed records by spec over the last 10 minutes
sum by(specId) (increase(transform_records_failed_total[10m]))

# JVM heap used vs committed
jvm_memory_used_bytes{area="heap"}
jvm_memory_committed_bytes{area="heap"}

# Active HTTP connections
tomcat_connections_current_connections

# Kafka producer record send rate
rate(kafka_producer_record_send_total[1m])

# App uptime
process_uptime_seconds
```

### Check scrape targets

Go to **Status → Targets** in the Prometheus UI (http://localhost:9090/targets) to confirm:
- `transform-platform` target is `UP`
- `otel-collector` target is `UP`

If a target shows `DOWN`, the app may not be running or the actuator endpoint is unreachable.

---

## 7. Grafana (port 3001)

**URL:** http://localhost:3001
**Username:** `admin`
**Password:** `admin`

> Grafana is mapped to host port **3001** (not 3000) to avoid a conflict with the Docusaurus dev server.

Grafana starts pre-configured with two datasources (auto-provisioned — no manual setup needed):
- **Prometheus** → connected to `http://prometheus:9090`
- **Jaeger** → connected to `http://jaeger:16686`

### Pre-built dashboard

A Transform Platform dashboard is auto-loaded from `.docker/grafana/dashboards/transform-platform.json`. Find it at:

**Dashboards → Browse → Transform Platform**

It shows:
- Records processed / failed / corrected over time
- File processing duration p50 / p95 / p99
- Kafka publish duration
- Active pipeline jobs (gauge)
- JVM heap and GC pressure

### Changing the admin password

Go to **Profile → Change Password** after first login. The docker-compose env vars only set the initial password.

### Exploring traces in Grafana

1. Open **Explore** (compass icon in the left sidebar)
2. Select the **Jaeger** datasource
3. Search by service `transform-platform`
4. Click any trace to see the full span waterfall

---

## 8. Jaeger — Distributed Tracing (port 16686)

**URL:** http://localhost:16686

No credentials required. Every HTTP request and Kafka publish made by the app is automatically traced via OpenTelemetry. Traces are exported: app → OTel Collector → Jaeger.

### Finding a trace

1. Open http://localhost:16686
2. In **Service**, select `transform-platform`
3. Optionally filter by **Operation** (e.g. `POST /api/v1/transform/file-to-events`)
4. Set a time range and click **Find Traces**
5. Click any trace row to open the span waterfall

### Correlating a trace with logs

Every log line includes `traceId` and `spanId` fields (injected by MDC via `CorrelationIdFilter`). To find all logs for a specific request:

1. Copy the `traceId` from the Jaeger trace detail view
2. Go to Kibana → **Discover**
3. Search: `traceId: "<paste-here>"`

---

## 9. Elasticsearch (port 9200) + Kibana (port 5601)

Security is **disabled** in local dev (`xpack.security.enabled=false`). No credentials needed.

### Elasticsearch quick checks

```bash
# Cluster health
curl http://localhost:9200/_cluster/health | jq

# List all indices
curl http://localhost:9200/_cat/indices?v

# Count documents in the app's log index
curl http://localhost:9200/transform-platform-*/_count | jq
```

### Kibana — Log Explorer

**URL:** http://localhost:5601

#### First-time setup (one-time only)

1. Open http://localhost:5601
2. Go to **Stack Management** (gear icon, bottom left) → **Data Views**
3. Click **Create data view**
4. **Name:** `Transform Platform Logs`
5. **Index pattern:** `transform-platform-*`
6. **Timestamp field:** `@timestamp`
7. Click **Save data view to Kibana**

#### Using Discover

1. Go to **Discover** (compass icon)
2. Select **Transform Platform Logs** from the data view dropdown
3. Set the time range (top right) — try **Last 1 hour** to start
4. Use KQL to filter:

```kql
# All errors
level: "ERROR"

# Errors from a specific class
level: "ERROR" and logger_name: *TransformationPipeline*

# All logs for a specific trace (end-to-end request view)
traceId: "your-trace-id-here"

# Warnings and errors together
level: "ERROR" or level: "WARN"
```

#### Useful log fields

| Field | Description |
|---|---|
| `@timestamp` | When the log was written |
| `level` | Log level: TRACE / DEBUG / INFO / WARN / ERROR |
| `logger_name` | Fully-qualified class name |
| `message` | Log message text |
| `traceId` | OTel trace ID — links to Jaeger |
| `spanId` | OTel span ID |
| `correlationId` | HTTP request correlation ID (set by `CorrelationIdFilter`) |
| `thread_name` | Thread that logged the event |
| `stack_trace` | Exception stack trace (for ERROR logs) |

---

## 10. OTel Collector (internal)

The OTel Collector is infrastructure plumbing — you do not interact with it directly. It:

1. Receives traces from the app on port `4318` (OTLP HTTP)
2. Forwards traces to Jaeger on port `14250`
3. Forwards logs to Elasticsearch on port `9200`
4. Exposes its own metrics at port `8889` (scraped by Prometheus)

### Health check

```bash
curl http://localhost:13133/
# Returns: {"status": "Server available", "upSince": "..."}
```

Configuration is in `otel-collector-config.yaml`.

---

## Troubleshooting

### App won't start — database connection refused

Postgres may still be initialising. Wait 10–15 seconds and check:

```bash
docker ps --format "table {{.Names}}\t{{.Status}}" | grep transform
```

All containers should show `healthy` or `Up`. Then try starting the app again.

### App starts but Kafka health shows DOWN

Kafka takes 20–30 seconds after Zookeeper is ready. Run:

```bash
docker logs transform-kafka --tail 20
```

Look for `[KafkaServer id=1] started` — once you see that line, Kafka is ready.

### Prometheus target shows DOWN

Verify the app actuator is reachable:

```bash
curl http://localhost:8080/actuator/prometheus | head -5
```

If that fails, check app logs. If it succeeds, reload the Prometheus targets page.

### Grafana shows "No data"

1. Confirm Prometheus target is UP at http://localhost:9090/targets
2. In Grafana, open the dashboard, click any panel → **Edit** → **Run query**
3. If the metric name changed, the PromQL query may need updating

### Kibana shows no logs

Logs only flow to Elasticsearch when the app is using the JSON log profile. The `dev-text` profile writes human-readable logs to stdout only.

- **For local debugging with visible logs:** keep `dev-text` in `SPRING_PROFILES_ACTIVE`
- **For Kibana:** remove `dev-text` from `SPRING_PROFILES_ACTIVE` to activate the JSON/Logstash appender

### Reset a single service's data

```bash
# Example: wipe and restart Elasticsearch only
docker compose -f .docker/docker-compose.yml stop elasticsearch
docker volume rm transform-platform_elasticsearch_data
docker compose -f .docker/docker-compose.yml up -d elasticsearch
```

### Port already in use

```bash
# Find what is using a port (macOS / Linux)
lsof -i :3001

# Windows
netstat -ano | findstr :3001
```
