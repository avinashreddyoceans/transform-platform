# Quick Reference

## Start Here

```
1. Run Docker:  docker compose -f .docker/docker-compose.yml up -d
2. Start app:   ./gradlew :platform-api:bootRun
3. Check health: health/01-health.http
4. Create spec:  specs/02-spec-csv.http
5. Transform:    transform/05-transform.http
```

## Structure at a Glance

```
http/
├── README.md                          ← You are here
│
├── health/                            ← App health & metrics
│   └── 01-health.http
│
├── specs/                             ← File format definitions (CRUD)
│   ├── 02-spec-csv.http
│   ├── 03-spec-fixed-width.http
│   └── 04-spec-xml.http
│
├── transform/                         ← File transformation (parse & publish)
│   └── 05-transform.http
│
├── observability/                     ← Monitoring & dashboards
│   └── 06-observability.http
│
├── config/                            ← Environment & client configs
│   ├── http-client.env.json
│   ├── http-client.private.env.json
│   ├── transform-platform.postman_collection.json
│   └── transform-platform.postman_environment.json
│
└── samples/                           ← Sample test data (add as needed)
```

## Quick Links

| Need | File | Action |
|------|------|--------|
| **Is the app healthy?** | `health/01-health.http` | `GET /actuator/health` |
| **Create a CSV spec** | `specs/02-spec-csv.http` | `POST /api/v1/specs` |
| **Transform a file** | `transform/05-transform.http` | `POST /api/v1/transform/file-to-events` |
| **View metrics** | `observability/06-observability.http` | `GET /actuator/prometheus` |
| **Swagger UI** | `observability/06-observability.http` | Open browser to `/swagger-ui` |
| **Kafka UI** | Dashboard link | http://localhost:8090 |
| **Prometheus** | Dashboard link | http://localhost:9090 |
| **Grafana** | Dashboard link | http://localhost:3001 (admin/admin) |
| **Jaeger traces** | Dashboard link | http://localhost:16686 |
| **Kibana logs** | Dashboard link | http://localhost:5601 |

## Environment Setup

- Edit `config/http-client.env.json` to set:
  - `baseUrl` (default: `http://localhost:8080`)
  - `csvSpecId` (set after creating a CSV spec)
  - `kafkaTopic` (default: `transform.records.local`)

- Sensitive values in `config/http-client.private.env.json` (git-ignored)

## IntelliJ Tips

- **Send request:** `Ctrl+Alt+Enter` (macOS: `⌘+⌥+Enter`)
- **View response raw:** Right-click response → **Raw**
- **Save response:** Right-click response → **Save Response Body**
- **Environment variables:** Auto-loaded from `config/http-client.env.json`

## Postman Tips

- **Import collection:** `config/transform-platform.postman_collection.json`
- **Import environment:** `config/transform-platform.postman_environment.json`
- **Select environment:** Top-right environment dropdown

