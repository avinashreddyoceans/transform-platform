---
id: observability
title: Observability
sidebar_position: 5
---

# Observability

Transform Platform ships a full observability stack out of the box. A single `docker compose up` gives you structured logs, distributed traces, and real-time metrics — all correlated by the same `traceId`.

---

## The Three Pillars

```mermaid
graph LR
    L["📋 Logs\nStructured JSON\nper request"]
    T["🔍 Traces\nDistributed spans\nacross services"]
    M["📊 Metrics\nCounters, histograms\nand gauges"]

    L --- C(["correlationId\ntraceId\nspanId"])
    T --- C
    M --- C

    style C fill:#dbeafe,stroke:#2563eb,color:#1e3a8a
    style L fill:#f0fdf4,stroke:#16a34a
    style T fill:#fef9c3,stroke:#ca8a04
    style M fill:#fdf4ff,stroke:#9333ea
```

Every log line, every trace span, and every metric tag carries the same identifiers so you can jump between tools without losing context.

---

## Signal Flow — How Everything Connects

```mermaid
flowchart TD
    APP["🚀 Transform Platform App\nlocalhost:8080"]

    subgraph SIGNALS["Signals emitted by the app"]
        direction LR
        T2["Traces\nOTLP/HTTP :4318"]
        M2["Metrics\n/actuator/prometheus"]
        L2["Logs\nOTLP/HTTP :4318"]
    end

    APP -->|"micrometer-tracing-bridge-otel\n+ opentelemetry-exporter-otlp"| T2
    APP -->|"micrometer-registry-prometheus\nActuator endpoint"| M2
    APP -->|"logstash-logback-encoder\nOTLP log exporter"| L2

    OTEL["⚙️ OTel Collector\n:4317 gRPC · :4318 HTTP\n:13133 health"]

    T2 -->|OTLP HTTP| OTEL
    L2 -->|OTLP HTTP| OTEL

    JAEGER["🔍 Jaeger\nlocalhost:16686"]
    ES["🗂️ Elasticsearch\nlocalhost:9200"]
    PROM["📊 Prometheus\nlocalhost:9090"]

    OTEL -->|"OTLP gRPC\n:14250"| JAEGER
    OTEL -->|"HTTP bulk\n:9200"| ES
    M2  -->|"scrape every 15s"| PROM

    GRAFANA["📈 Grafana\nlocalhost:3001"]
    KIBANA["🔎 Kibana\nlocalhost:5601"]

    PROM  -->|PromQL datasource| GRAFANA
    JAEGER -->|Jaeger datasource| GRAFANA
    ES    -->|index pattern| KIBANA

    style APP   fill:#dbeafe,stroke:#2563eb
    style OTEL  fill:#fef9c3,stroke:#ca8a04
    style JAEGER fill:#fdf4ff,stroke:#9333ea
    style ES    fill:#ecfdf5,stroke:#10b981
    style PROM  fill:#fff7ed,stroke:#ea580c
    style GRAFANA fill:#fdf2f8,stroke:#db2777
    style KIBANA fill:#ecfdf5,stroke:#10b981
```

---

## Component Responsibilities

### App — what it emits

| Signal | Library | Transport | Destination |
|---|---|---|---|
| **Traces** | `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` | OTLP/HTTP to `:4318` | OTel Collector |
| **Metrics** | `micrometer-registry-prometheus` | Actuator pull at `/actuator/prometheus` | Prometheus (scrapes every 15s) |
| **Logs** | `logstash-logback-encoder` via Logback `FILE` appender | Structured JSON file + OTel log exporter | OTel Collector → Elasticsearch |

Every HTTP request gets a `correlationId` injected into MDC by `CorrelationIdFilter`. Spring's OTel bridge then automatically adds the active `traceId` and `spanId` to MDC as well, so every log line carries all three identifiers.

### OTel Collector — the routing hub

The Collector (`:4318`) receives both traces and logs from the app over OTLP/HTTP. It:

- Routes **traces** → Jaeger via OTLP gRPC (`:14250`)
- Routes **logs** → Elasticsearch via HTTP bulk API (`:9200`)
- Exposes **its own metrics** at `:8889` (scraped by Prometheus)

Config: `.docker/otel-collector-config.yaml`

### Prometheus — metrics store

Prometheus pulls metrics from two sources every 15 seconds:

1. `/actuator/prometheus` on the app — all JVM + custom business metrics
2. `:8889` on the OTel Collector — collector pipeline metrics

Config: `.docker/prometheus.yml`

### Grafana — unified dashboard

Grafana connects to both Prometheus and Jaeger as datasources (auto-provisioned at startup). The pre-built **Transform Platform** dashboard correlates metrics and traces in one view. Config: `.docker/grafana/provisioning/`

### Elasticsearch + Kibana — log store

Structured JSON logs are forwarded from the OTel Collector to Elasticsearch. Kibana provides the search and visualisation layer. First-time setup requires creating a data view with pattern `transform-platform-*`.

---

## Trace Lifecycle — One Request End to End

```mermaid
sequenceDiagram
    participant Client
    participant App as App :8080
    participant DB as PostgreSQL
    participant Kafka
    participant OTel as OTel Collector
    participant Jaeger
    participant ES as Elasticsearch

    Client->>App: POST /api/v1/transform/file-to-events
    Note over App: CorrelationIdFilter sets MDC:<br/>correlationId, traceId, spanId

    App->>DB: SELECT spec by ID
    Note over App,DB: child span: db.query

    App->>App: Parse → Correct → Validate records
    Note over App: child spans per pipeline stage

    App->>Kafka: Publish N records
    Note over App,Kafka: child span: kafka.producer.send

    App-->>Client: 200 TransformResponse {correlationId, totalRecords...}

    App-)OTel: OTLP/HTTP — trace spans (async)
    App-)OTel: OTLP/HTTP — log events (async)

    OTel-)Jaeger: OTLP gRPC — full trace
    OTel-)ES: HTTP bulk — log events with traceId
```

Every span in Jaeger links back to the same `traceId` present in every log line. This means you can:

1. Find an error in **Kibana** → copy `traceId`
2. Paste into **Jaeger** search → see exactly which span failed and how long each step took
3. Switch to **Grafana** → view the metrics spike that coincided with the error

---

## Metrics Reference

All custom metrics are registered in `TransformMetrics.kt` using Micrometer. Tags enable per-spec filtering in Prometheus and Grafana.

```mermaid
graph LR
    subgraph Counters
        P["transform.records.processed\ntags: specId, status"]
        F["transform.records.failed\ntags: specId"]
        C["transform.records.corrected\ntags: specId"]
        W["transform.records.warnings\ntags: specId"]
    end

    subgraph Histograms
        FT["transform.file.processing.time\ntags: specId, format\np50 · p95 · p99"]
        KT["transform.kafka.publish.time\ntags: topic\np50 · p95 · p99"]
    end

    subgraph Gauges
        AJ["transform.pipeline.active.jobs\ncurrent in-flight jobs"]
    end
```

### Key PromQL queries

```promql
# Throughput — records processed per minute (last 5m)
rate(transform_records_processed_total[5m]) * 60

# Error rate — failed as a percentage of total
rate(transform_records_failed_total[5m])
  / rate(transform_records_processed_total[5m])

# p99 processing time per spec
histogram_quantile(0.99,
  sum by(specId, le)(
    rate(transform_file_processing_duration_seconds_bucket[5m])
  )
)

# Specs with the most failures in the last hour
topk(5,
  sum by(specId)(increase(transform_records_failed_total[1h]))
)
```

---

## Log Structure

Every log line is emitted as JSON (when `dev-text` profile is not active). Fields injected automatically:

```json
{
  "@timestamp":    "2025-01-15T14:32:01.123Z",
  "level":         "ERROR",
  "logger_name":   "com.transformplatform.core.pipeline.TransformationPipeline",
  "message":       "Pipeline execution failed for spec=abc-123",
  "traceId":       "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId":        "00f067aa0ba902b7",
  "correlationId": "req-7f3a9c12",
  "thread_name":   "virtual-23",
  "stack_trace":   "com.transformplatform..."
}
```

`correlationId` is set per-request by `CorrelationIdFilter` (from `X-Correlation-ID` header or auto-generated UUID).
`traceId` and `spanId` are set automatically by Spring's OTel bridge from the active trace context.

---

## Developer Workflow — Debugging a Failed Transform

This is the recommended workflow when a transform job produces unexpected results or errors.

```mermaid
flowchart TD
    START(["Something went wrong\nafter POST /transform/file-to-events"])

    START --> RESP["1️⃣  Check the response body\ncorrelationId, failedRecords, errors[]"]

    RESP --> KIBA["2️⃣  Kibana → Discover\nKQL: correlationId: 'your-id'\nSee all log lines for that request"]

    KIBA --> TRACE["3️⃣  Copy traceId from any log line\nJaeger → search by traceId\nSee full span waterfall + timing"]

    TRACE --> SLOW{Slow span?}

    SLOW -->|DB query slow| DB["Check PostgreSQL\nEXPLAIN ANALYZE the query"]
    SLOW -->|Kafka publish slow| KF["Kafka UI → check broker lag\nPrometheus: kafka.producer metrics"]
    SLOW -->|Pipeline slow| PROM["Prometheus / Grafana\nhistogram_quantile p99 by specId"]

    TRACE --> ERR{Error span?}

    ERR -->|Validation failures| KIBLOG["Kibana: level:ERROR\nlogger_name: *ValidationEngine*"]
    ERR -->|Parser crash| KIBLOG2["Kibana: level:ERROR\nlogger_name: *FileParser*\ncheck stack_trace field"]

    style START fill:#fef2f2,stroke:#dc2626
    style KIBA  fill:#ecfdf5,stroke:#10b981
    style TRACE fill:#fdf4ff,stroke:#9333ea
    style PROM  fill:#fff7ed,stroke:#ea580c
    style DB    fill:#dbeafe,stroke:#2563eb
```

---

## Accessing the Tools

| Tool | URL | First steps |
|---|---|---|
| **Swagger UI** | http://localhost:8080/swagger-ui | Open in browser — no setup |
| **Actuator health** | http://localhost:8080/actuator/health | Open in browser or Postman |
| **Kafka UI** | http://localhost:8090 | Open in browser — browse topics and messages |
| **Prometheus** | http://localhost:9090 | Paste PromQL into the Expression bar → Execute |
| **Grafana** | http://localhost:3001 | Login `admin`/`admin` → Dashboards → Transform Platform |
| **Jaeger** | http://localhost:16686 | Select service `transform-platform` → Find Traces |
| **Kibana** | http://localhost:5601 | First visit: create data view `transform-platform-*` |

See `.docker/README.md` for detailed connection instructions, credentials, CLI commands, and troubleshooting.

---

## Configuration Files

| File | Purpose |
|---|---|
| `.docker/docker-compose.yml` | Defines all services, ports, and volumes |
| `.docker/otel-collector-config.yaml` | OTel Collector pipeline — receivers, processors, exporters |
| `.docker/prometheus.yml` | Prometheus scrape config — targets and intervals |
| `.docker/grafana/provisioning/datasources.yml` | Auto-provisions Prometheus + Jaeger datasources |
| `.docker/grafana/dashboards/transform-platform.json` | Pre-built Grafana dashboard |
| `platform-api/src/main/resources/logback-spring.xml` | Logback config — JSON appender + rolling file |
| `platform-api/src/main/resources/application.yml` | OTLP exporter endpoint, tracing sample rate |
| `platform-api/src/main/kotlin/.../metrics/TransformMetrics.kt` | All custom Micrometer meters |
| `platform-api/src/main/kotlin/.../filter/CorrelationIdFilter.kt` | Injects `correlationId` into MDC per request |
| `platform-api/src/main/kotlin/.../config/ObservabilityConfig.kt` | Global meter tags — service name, environment |
