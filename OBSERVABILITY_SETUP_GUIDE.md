# Observability Stack Setup Guide
### Jaeger · Prometheus · OTel Collector · Elasticsearch · Kibana · Grafana

This guide covers everything needed to replicate the full observability stack from the transform-platform project in any new Spring Boot application.

---

## Architecture Overview

```
Spring Boot App
│
│  Traces  ──OTLP HTTP──▶  OTel Collector ──▶  Jaeger       (UI: :16686)
│  Metrics ──OTLP HTTP──▶  OTel Collector ──▶  Prometheus   (UI: :9090)
│                                           └──▶  Grafana       (UI: :3001)
│  Logs (JSON file)  ◀── filelog receiver ──────  OTel Collector ──▶  Elasticsearch ──▶  Kibana  (UI: :5601)
│
│  /actuator/prometheus ◀── Prometheus scrapes directly (Micrometer pull model)
```

**Signal routing summary:**
| Signal  | App → Collector | Collector → Backend |
|---------|----------------|---------------------|
| Traces  | OTLP HTTP :4318 | Jaeger gRPC :14250 |
| Metrics | OTLP HTTP :4318 | Prometheus scrapes :8889 |
| Logs    | JSON file tail  | Elasticsearch HTTP :9200 |

---

## Part 1 — Docker Compose Stack

Create `.docker/docker-compose.yml`. Copy this entire file — it is the authoritative working version.

```yaml
version: '3.9'

services:

  # ── Data Layer ─────────────────────────────────────────────────────────────

  postgres:
    image: postgres:16-alpine
    container_name: myapp-postgres
    environment:
      POSTGRES_DB: myapp
      POSTGRES_USER: myapp_user
      POSTGRES_PASSWORD: myapp_pass
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U myapp_user -d myapp"]
      interval: 10s
      timeout: 5s
      retries: 5
    restart: unless-stopped

  # ── Distributed Tracing ────────────────────────────────────────────────────

  jaeger:
    image: jaegertracing/all-in-one:1.56
    container_name: myapp-jaeger
    environment:
      COLLECTOR_OTLP_ENABLED: "true"
      SPAN_STORAGE_TYPE: memory
      MEMORY_MAX_TRACES: 50000
    ports:
      - "16686:16686"   # Jaeger UI
      - "14250:14250"   # gRPC receiver (from OTel Collector)
    restart: unless-stopped

  # ── Metrics ────────────────────────────────────────────────────────────────

  prometheus:
    image: prom/prometheus:v2.51.2
    container_name: myapp-prometheus
    command:
      - "--config.file=/etc/prometheus/prometheus.yml"
      - "--storage.tsdb.retention.time=7d"
      - "--web.enable-lifecycle"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheus_data:/prometheus
    ports:
      - "9090:9090"
    depends_on:
      - otel-collector
    restart: unless-stopped

  # ── Dashboards ─────────────────────────────────────────────────────────────

  grafana:
    image: grafana/grafana:10.4.2
    container_name: myapp-grafana
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: admin
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_FEATURE_TOGGLES_ENABLE: traceqlEditor
    volumes:
      - grafana_data:/var/lib/grafana
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
    ports:
      - "3001:3000"   # 3000 often taken by front-end dev servers
    depends_on:
      - prometheus
      - jaeger
    restart: unless-stopped

  # ── Log Storage ────────────────────────────────────────────────────────────

  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.13.2
    container_name: myapp-elasticsearch
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
      - ES_JAVA_OPTS=-Xms512m -Xmx512m
    ulimits:
      memlock:
        soft: -1
        hard: -1
    volumes:
      - elasticsearch_data:/usr/share/elasticsearch/data
    ports:
      - "9200:9200"
    healthcheck:
      test: ["CMD-SHELL", "curl -s http://localhost:9200/_cluster/health | grep -q '\"status\":\"green\"\\|\"status\":\"yellow\"'"]
      interval: 15s
      timeout: 10s
      retries: 5
    restart: unless-stopped

  kibana:
    image: docker.elastic.co/kibana/kibana:8.13.2
    container_name: myapp-kibana
    environment:
      ELASTICSEARCH_HOSTS: http://elasticsearch:9200
      XPACK_SECURITY_ENABLED: "false"
    ports:
      - "5601:5601"
    depends_on:
      elasticsearch:
        condition: service_healthy
    restart: unless-stopped

  # One-shot: creates the Kibana data view so logs appear immediately.
  kibana-setup:
    image: curlimages/curl:8.6.0
    container_name: myapp-kibana-setup
    depends_on:
      kibana:
        condition: service_healthy
    restart: "no"
    entrypoint: >
      sh -c '
        curl -sf -X POST http://kibana:5601/api/data_views/data_view
          -H "kbn-xsrf: true"
          -H "Content-Type: application/json"
          -d "{\"data_view\":{\"title\":\"myapp-logs*\",\"name\":\"My App Logs\",\"timeFieldName\":\"@timestamp\"}}"
        && echo "Data view created." || echo "Data view may already exist.";
      '

  # ── OTel Collector — central signal router ─────────────────────────────────

  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.98.0
    container_name: myapp-otel-collector
    command: ["--config=/etc/otel/config.yaml"]
    volumes:
      - ./otel-collector-config.yaml:/etc/otel/config.yaml:ro
      # Bind-mount the app log directory so the filelog receiver can tail it.
      # App writes JSON logs to ./logs/myapp.log on the host.
      - ../logs:/var/log/app:ro
    ports:
      - "4317:4317"    # OTLP gRPC (optional — app uses HTTP)
      - "4318:4318"    # OTLP HTTP
      - "8889:8889"    # Prometheus metrics exporter
      - "13133:13133"  # Health check
    depends_on:
      - jaeger
      - elasticsearch
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:13133/"]
      interval: 10s
      timeout: 5s
      retries: 3

volumes:
  postgres_data:
  prometheus_data:
  grafana_data:
  elasticsearch_data:
```

---

## Part 2 — OTel Collector Config

Create `.docker/otel-collector-config.yaml`:

```yaml
# Signal routing:
#   Traces  → Jaeger       (gRPC :14250)
#   Metrics → Prometheus   (pull from :8889)
#   Logs    → Elasticsearch (:9200) via filelog receiver tailing the app log file

receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318
        cors:
          allowed_origins: ["*"]

  # Tail the JSON log file written by Logback + LogstashEncoder.
  # The app runs on the host; the log directory is bind-mounted at /var/log/app.
  filelog:
    include: [/var/log/app/myapp.log]    # ← match your log file name
    start_at: beginning
    operators:
      - type: json_parser
        parse_from: body
        timestamp:
          parse_from: attributes.timestamp
          layout_type: gotime
          layout: "2006-01-02T15:04:05.000000-07:00"
        severity:
          parse_from: attributes.level
          preset: default
      - type: move
        from: attributes.message
        to: body
      # Carry trace correlation IDs as standard OTel log attributes
      - type: move
        from: attributes.traceId
        to: attributes["traceId"]
        if: 'attributes["traceId"] != nil'
      - type: move
        from: attributes.correlationId
        to: attributes["correlationId"]
        if: 'attributes["correlationId"] != nil'
      - type: move
        from: attributes.logger_name
        to: attributes["logger.name"]
        if: 'attributes["logger_name"] != nil'
      - type: move
        from: attributes.thread_name
        to: attributes["thread.name"]
        if: 'attributes["thread_name"] != nil'

processors:
  batch:
    send_batch_size: 1000
    timeout: 10s

  resource:
    attributes:
      - key: deployment.environment
        value: local
        action: upsert

  # Strip auth headers — never store them in traces
  attributes/sanitize:
    actions:
      - key: http.request.header.authorization
        action: delete
      - key: http.response.header.set-cookie
        action: delete

exporters:
  otlp/jaeger:
    endpoint: jaeger:4317
    tls:
      insecure: true

  prometheus:
    endpoint: 0.0.0.0:8889
    namespace: myapp             # ← change to your app name
    const_labels:
      source: otel_collector

  elasticsearch:
    endpoints: ["http://elasticsearch:9200"]
    logs_index: myapp-logs       # ← change to your app name
    retry:
      enabled: true
      initial_interval: 1s
      max_interval: 30s

  debug:
    verbosity: basic
    sampling_initial: 5
    sampling_thereafter: 100

extensions:
  health_check:
    endpoint: 0.0.0.0:13133

service:
  extensions: [health_check]
  pipelines:
    traces:
      receivers:  [otlp]
      processors: [resource, attributes/sanitize, batch]
      exporters:  [otlp/jaeger]

    metrics:
      receivers:  [otlp]
      processors: [resource, batch]
      exporters:  [prometheus]

    logs:
      receivers:  [filelog]
      processors: [resource, batch]
      exporters:  [elasticsearch]
```

---

## Part 3 — Prometheus Config

Create `.docker/prometheus.yml`:

```yaml
global:
  scrape_interval:     15s
  evaluation_interval: 15s
  external_labels:
    monitor: myapp-local

scrape_configs:

  # Spring Boot app — Micrometer pull model via /actuator/prometheus
  - job_name: myapp
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['host.docker.internal:8080']   # app on host; change port if needed
        labels:
          service: myapp
          env: local

  # OTel Collector self-metrics
  - job_name: otel-collector
    static_configs:
      - targets: ['otel-collector:8889']
        labels:
          service: otel-collector
          env: local
```

> **Note:** `host.docker.internal` resolves to the host machine from inside Docker on Mac and Windows. On Linux, add `--add-host=host.docker.internal:host-gateway` to the Prometheus container, or use your actual host IP.

---

## Part 4 — Grafana Datasources

Create `.docker/grafana/provisioning/datasources/datasources.yml`:

```yaml
apiVersion: 1

datasources:

  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: true
    jsonData:
      timeInterval: "15s"
      # Enables "jump to trace" links on Prometheus exemplar data points
      exemplarTraceIdDestinations:
        - name: traceID
          datasourceUid: jaeger

  - name: Jaeger
    type: jaeger
    uid: jaeger
    access: proxy
    url: http://jaeger:16686
    editable: true
    jsonData:
      # Enables "view logs for this trace" links in Jaeger
      tracesToLogsV2:
        datasourceUid: elasticsearch
        filterByTraceID: true
        filterBySpanID: false

  - name: Elasticsearch
    type: elasticsearch
    uid: elasticsearch
    access: proxy
    url: http://elasticsearch:9200
    editable: true
    jsonData:
      index: myapp-logs          # ← match your ES index name
      timeField: "@timestamp"
      logMessageField: message
      logLevelField: level
```

Create `.docker/grafana/provisioning/dashboards/dashboards.yml`:

```yaml
apiVersion: 1
providers:
  - name: default
    type: file
    disableDeletion: false
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards
```

---

## Part 5 — Gradle Dependencies

Add these to your `build.gradle.kts` (the Spring Boot application module):

```kotlin
dependencies {
    // ── Micrometer (metrics) ─────────────────────────────────────────────────
    // Exposes /actuator/prometheus — Prometheus scrapes this endpoint
    implementation("io.micrometer:micrometer-registry-prometheus")

    // ── OTel Bridge (traces) ─────────────────────────────────────────────────
    // Bridges Micrometer's Observation API to OpenTelemetry SDK
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    // Exports spans via OTLP (HTTP) to the OTel Collector
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // ── Structured logging ───────────────────────────────────────────────────
    // LogstashEncoder — writes JSON logs that the OTel Collector filelog receiver parses
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    // Spring Boot Actuator — required for /actuator/prometheus and /actuator/health
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}
```

No explicit versions needed for `micrometer-*` and `opentelemetry-*` — Spring Boot's BOM manages them.

---

## Part 6 — application.yml

Add the following blocks to your `application.yml`:

```yaml
spring:
  application:
    name: myapp   # ← appears as service.name in all OTel signals

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,traces
  endpoint:
    health:
      show-details: always
  tracing:
    sampling:
      probability: 1.0   # 100% in dev; use 0.05-0.1 in production
  otlp:
    tracing:
      endpoint: http://${OTEL_COLLECTOR_HOST:localhost}:4318/v1/traces

logging:
  level:
    # Suppress noisy OTel startup logs; raise to DEBUG when diagnosing issues
    io.opentelemetry: WARN
    io.micrometer.tracing: WARN
```

For `application-local.yml` (optional profile for local dev):

```yaml
management:
  tracing:
    enabled: ${OTEL_ENABLED:true}   # set OTEL_ENABLED=false to run without Docker stack
```

---

## Part 7 — logback-spring.xml

Place this in `src/main/resources/logback-spring.xml`.
The key points: LogstashEncoder writes structured JSON; the log file is tailed by the OTel Collector filelog receiver.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>

    <springProperty scope="context" name="appName" source="spring.application.name" defaultValue="myapp"/>
    <springProperty scope="context" name="appEnv"  source="spring.profiles.active"  defaultValue="local"/>

    <!-- Rolling JSON file — tailed by OTel Collector filelog receiver -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${user.dir}/logs/myapp.log</file>  <!-- must match otel-collector-config.yaml filelog path -->
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${user.dir}/logs/myapp.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>50MB</maxFileSize>
            <maxHistory>7</maxHistory>
            <totalSizeCap>200MB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <fieldNames>
                <timestamp>timestamp</timestamp>
                <version>[ignore]</version>
                <levelValue>[ignore]</levelValue>
            </fieldNames>
            <customFields>{"service":"${appName}","env":"${appEnv}"}</customFields>
            <!-- Include trace correlation IDs — written to MDC by TracingMdcFilter -->
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <includeMdcKeyName>correlationId</includeMdcKeyName>
            <throwableConverter class="net.logstash.logback.stacktrace.ShortenedThrowableConverter">
                <maxDepthPerCause>20</maxDepthPerCause>
                <rootCauseFirst>true</rootCauseFirst>
            </throwableConverter>
        </encoder>
    </appender>

    <!-- JSON console appender -->
    <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <fieldNames>
                <timestamp>timestamp</timestamp>
                <version>[ignore]</version>
                <levelValue>[ignore]</levelValue>
            </fieldNames>
            <customFields>{"service":"${appName}","env":"${appEnv}"}</customFields>
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <includeMdcKeyName>correlationId</includeMdcKeyName>
        </encoder>
    </appender>

    <!-- Human-readable console for local development (activate with -Dspring.profiles.active=dev-text) -->
    <appender name="TEXT_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %highlight(%-5level) [%X{traceId:-no-trace}] %cyan(%logger{36}) - %msg%n%ex</pattern>
        </encoder>
    </appender>

    <springProfile name="dev-text">
        <root level="INFO">
            <appender-ref ref="TEXT_CONSOLE"/>
            <appender-ref ref="FILE"/>
        </root>
    </springProfile>

    <springProfile name="!dev-text">
        <root level="INFO">
            <appender-ref ref="JSON_CONSOLE"/>
            <appender-ref ref="FILE"/>
        </root>
    </springProfile>

    <logger name="com.yourpackage" level="DEBUG"/>

</configuration>
```

> **Critical:** The log file path in `<file>` must exactly match the `include` path in the OTel Collector's `filelog` receiver after bind-mount resolution. In docker-compose the host's `./logs/` maps to `/var/log/app/` inside the collector.

---

## Part 8 — Application Code

### 8a. ObservabilityConfig.kt — common metric tags

```kotlin
@Configuration
class ObservabilityConfig {

    @Value("\${spring.application.name:myapp}")
    private lateinit var appName: String

    @Value("\${spring.profiles.active:local}")
    private lateinit var activeProfile: String

    @Bean
    fun commonTagsCustomizer(): MeterRegistryCustomizer<MeterRegistry> =
        MeterRegistryCustomizer { registry ->
            registry.config().commonTags(
                "service", appName,
                "env",     activeProfile,
            )
        }
}
```

### 8b. TracingMdcFilter.kt — writes traceId/spanId to MDC for HTTP threads

```kotlin
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class TracingMdcFilter(private val tracer: Tracer) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val span = tracer.currentSpan()
        val hasTrace = span != null && span.context().traceId().isNotBlank()

        if (hasTrace) {
            MDC.put("traceId", span!!.context().traceId())
            MDC.put("spanId",  span.context().spanId())
        }
        try {
            filterChain.doFilter(request, response)
        } finally {
            if (hasTrace) {
                MDC.remove("traceId")
                MDC.remove("spanId")
            }
        }
    }
}
```

### 8c. OtelTracingAppender.kt — writes traceId/spanId to MDC for non-HTTP threads

For Kafka consumers, schedulers, and async tasks the Servlet filter doesn't run. This Logback appender intercepts every log event and enriches its MDC snapshot with the active OTel span at append time.

Copy `OtelTracingAppender.kt` from `platform-api/.../logging/` verbatim — no changes needed. Wire it in `logback-spring.xml` by wrapping the `FILE` appender:

```xml
<appender name="ASYNC_OTEL" class="com.yourpackage.logging.OtelTracingAppender">
    <appender-ref ref="FILE"/>
</appender>

<root level="INFO">
    <appender-ref ref="JSON_CONSOLE"/>
    <appender-ref ref="ASYNC_OTEL"/>   <!-- FILE goes through OtelTracingAppender -->
</root>
```

### 8d. Custom business metrics (optional)

```kotlin
@Component
class AppMetrics(private val registry: MeterRegistry) {

    fun recordOrderProcessed(status: String) =
        registry.counter("orders.processed", "status", status).increment()

    fun recordOrderDuration(status: String, durationMs: Long) =
        Timer.builder("orders.duration")
            .tag("status", status)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS)
}
```

These counters/timers appear automatically in Grafana under `myapp_orders_*`.

---

## Part 9 — Directory Structure

Your `.docker/` folder should look like this:

```
.docker/
├── docker-compose.yml
├── otel-collector-config.yaml
├── prometheus.yml
└── grafana/
    └── provisioning/
        ├── datasources/
        │   └── datasources.yml
        └── dashboards/
            └── dashboards.yml
```

The app writes logs to `./logs/` in the project root (relative to where you run the app from), which is bind-mounted into the OTel Collector container.

---

## Part 10 — Checklist for a New App

```
[ ] Copy .docker/ folder with all configs
[ ] Replace every "myapp" / "transform-platform" occurrence with your app name
[ ] Update prometheus.yml target port if your app doesn't run on :8080
[ ] Add Gradle dependencies (Part 5)
[ ] Add management.otlp + management.tracing to application.yml (Part 6)
[ ] Add logback-spring.xml with the correct log file name (Part 7)
[ ] Add ObservabilityConfig.kt (Part 8a)
[ ] Add TracingMdcFilter.kt (Part 8b)
[ ] Add OtelTracingAppender.kt + wire in logback-spring.xml (Part 8c)
[ ] Start stack: docker compose -f .docker/docker-compose.yml up -d
[ ] Hit an endpoint, then verify:
      Jaeger   → http://localhost:16686  (find your service in the dropdown)
      Grafana  → http://localhost:3001   (Prometheus datasource → Explore)
      Kibana   → http://localhost:5601   (Discover → select your data view)
```

---

## Quick Reference — Service URLs

| Service       | URL                                       | Credentials   |
|---------------|-------------------------------------------|---------------|
| Jaeger UI     | http://localhost:16686                    | none          |
| Prometheus    | http://localhost:9090                     | none          |
| Grafana       | http://localhost:3001                     | admin / admin |
| Kibana        | http://localhost:5601                     | none          |
| Elasticsearch | http://localhost:9200                     | none          |
| OTel Health   | http://localhost:13133                    | none          |
| App Metrics   | http://localhost:8080/actuator/prometheus | none          |
