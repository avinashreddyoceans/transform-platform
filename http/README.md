# HTTP Requests & API Documentation

This directory contains all HTTP requests for the Transform Platform API, organized by feature. Use these with:
- **IntelliJ IDEA** (built-in HTTP Client)
- **Postman** (via collection in `config/`)
- **curl** (copy-paste any request)

---

## Directory Structure

### 📋 `health/`
Spring Boot Actuator endpoints for monitoring app health and status.

- `01-health.http` — Health probes (liveness, readiness, full report)
- Endpoints: `/actuator/health/*`, `/actuator/info`, `/actuator/metrics`

**Use case:** Verify the app is up before running other requests.

---

### 📊 `specs/`
File format specification (CRUD operations).

- `02-spec-csv.http` — CSV format specs
- `03-spec-fixed-width.http` — Fixed-width format specs
- `04-spec-xml.http` — XML format specs

**Workflow:**
1. POST `/api/v1/specs` — Create a new spec
2. GET `/api/v1/specs/{id}` — Retrieve spec details
3. PUT `/api/v1/specs/{id}` — Update spec
4. POST `/api/v1/specs/{id}/validate` — Validate before first use
5. DELETE `/api/v1/specs/{id}` — Clean up

**Quick start:** Start with `02-spec-csv.http` to create your first spec.

---

### 🔄 `transform/`
File transformation operations (parse files and publish to Kafka).

- `05-transform.http` — Main transformation endpoints

**Endpoints:**
- POST `/api/v1/transform/file-to-events` — Parse file, publish records to Kafka immediately
- POST `/api/v1/transform/schedule` — Schedule a transformation job
- GET `/api/v1/transform/status/{id}` — Poll job status by correlation ID

**Prerequisites:** Create a spec first using `../specs/02-spec-csv.http`

---

### 📈 `observability/`
Monitoring, metrics, and tracing dashboard access.

- `06-observability.http` — API documentation links and observability endpoints

**Includes:**
- OpenAPI spec (JSON/YAML)
- Prometheus metrics export
- Links to monitoring dashboards:
  - Swagger UI: `http://localhost:8080/swagger-ui`
  - Prometheus: `http://localhost:9090`
  - Grafana: `http://localhost:3001` (admin / admin)
  - Jaeger: `http://localhost:16686`
  - Kibana: `http://localhost:5601`

---

### ⚙️ `config/`
Configuration and collection files for HTTP clients.

- `http-client.env.json` — IntelliJ HTTP Client environment variables
- `http-client.private.env.json` — Sensitive values (git-ignored)
- `transform-platform.postman_collection.json` — Postman collection
- `transform-platform.postman_environment.json` — Postman environment

**Setup:**
- **IntelliJ:** Environment variables are auto-loaded from `http-client.env.json`
- **Postman:** Import both the collection and environment JSON files

---

### 📁 `samples/`
Sample data files for testing file transformations.

(Files added as needed for different formats and test scenarios)

---

## Usage Guide

### With IntelliJ HTTP Client

1. Open any `.http` file in the `specs/`, `transform/`, `health/`, or `observability/` directories
2. Environment variables from `config/http-client.env.json` are auto-loaded
3. Click **Send Request** or press `Ctrl+Alt+Enter` (macOS: `⌘+⌥+Enter`)
4. Response appears in the editor panel below

### With Postman

1. **Import collection:** File → Import → select `config/transform-platform.postman_collection.json`
2. **Import environment:** File → Import → select `config/transform-platform.postman_environment.json`
3. Use the collection tree to explore and execute requests
4. Environment variables are available across all requests

### Prerequisites

Before running requests:

1. **Start Docker dependencies:**
   ```bash
   docker compose -f .docker/docker-compose.yml up -d
   ```

2. **Start the app:**
   ```bash
   ./gradlew :platform-api:bootRun
   # or use IntelliJ run config: "TransformPlatformApi - Local"
   ```

3. **Verify app is healthy:**
   ```bash
   curl http://localhost:8080/actuator/health
   # Expected: { "status": "UP" }
   ```

---

## Common Workflows

### 1. Create and Transform a CSV File

```
1. Open health/01-health.http → GET /actuator/health (verify app is UP)
2. Open specs/02-spec-csv.http → POST /api/v1/specs (create spec, copy ID)
3. Paste ID into http-client.env.json as csvSpecId
4. Open transform/05-transform.http → POST /api/v1/transform/file-to-events
5. Browse to a local CSV file and execute
6. Records appear in Kafka topic (visible via Kafka UI at http://localhost:8090)
```

### 2. Monitor Transformation Performance

```
1. Open observability/06-observability.http → GET /actuator/prometheus
2. Copy metrics, paste into Prometheus (http://localhost:9090)
3. Or view Grafana dashboard at http://localhost:3001
```

### 3. Trace an End-to-End Request

```
1. Execute a transform request (e.g., POST /api/v1/transform/file-to-events)
2. Copy the traceId from response headers or Kibana logs
3. Go to Jaeger (http://localhost:16686)
4. Paste traceId into search → view full span waterfall
```

---

## Environment Variables

Edit `config/http-client.env.json` to customize:

| Variable | Purpose | Default |
|---|---|---|
| `baseUrl` | API base URL | `http://localhost:8080` |
| `csvSpecId` | Spec ID for CSV tests | *(set after creating spec)* |
| `fixedWidthSpecId` | Spec ID for fixed-width tests | *(set after creating spec)* |
| `xmlSpecId` | Spec ID for XML tests | *(set after creating spec)* |
| `kafkaTopic` | Default Kafka topic | `transform.records.local` |
| `correlationId` | Request correlation ID | *(auto-generated if needed)* |

**Sensitive values** (e.g., API keys, passwords) go in `config/http-client.private.env.json` (git-ignored).

---

## Tips & Tricks

### Reuse Response Values
In any `.http` file, capture a value and reuse it in later requests:

```http
### Create spec and save the ID
POST {{baseUrl}}/api/v1/specs
# ... request body ...

> {%
  client.global.set("mySpecId", response.body.id);
%}

### Use the saved ID
GET {{baseUrl}}/api/v1/specs/{{mySpecId}}
```

### View Raw Response
Click **View → Response → Raw** in IntelliJ to see unparsed response.

### Save Large Responses
Right-click response panel → **Save Response Body**.

### Test & Assert
IntelliJ HTTP Client supports inline test scripts:

```http
> {%
  client.test("Status is 200", function() {
    client.assert(response.status === 200);
  });
  
  client.test("Body has spec ID", function() {
    client.assert(response.body.id !== null);
  });
%}
```

---

## Troubleshooting

### "Cannot resolve variable: baseUrl"
- Check `config/http-client.env.json` exists and is valid JSON
- IntelliJ may need a restart to reload environment files

### 401 Unauthorized
- Local dev has no auth by default
- Check Spring profile is `local` or `dev-text` in `.env` / app startup

### Multipart request fails in IntelliJ
- Click the folder icon next to the file path to browse and select a local file
- Ensure file exists at the path specified

### Postman environment not loading
- Right-click collection → **Edit** → **Select environment** dropdown (top right)

---

## See Also

- [Development Stack Guide](../.docker/README.md) — Docker setup, service ports, credentials
- [API Reference](../website/docs/api-reference.md) — Full endpoint documentation
- [Architecture Guide](../website/docs/architecture.md) — System design and data flow

