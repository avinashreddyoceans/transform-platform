---
title: Profile
description: The aggregate root that wires Window, Actions, and Integrations into a complete batch workflow
sidebar_position: 3
---

# Profile

A **Profile** is the aggregate root of the Transform Platform's domain model. It is the complete, self-contained configuration for any batch workflow — connecting a [Window](./window) (when things happen), [Actions](./action) (what happens), and [Integration Channels](#integration-channels) (where data flows in and out).

A single JSON or YAML Profile definition is all that's needed to onboard a new client, use case, or data flow — with zero code changes.

---

## Profile Structure

```
┌──────────────────────────────────────────────────────────────────┐
│                          PROFILE                                  │
│  id: OUTBOUND_FILE_USER_PROFILE_1                                 │
│  version: 3  ·  status: ENABLED                                   │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────┐     │
│  │  WINDOW CONFIG                                            │     │
│  │  startCron: "0 0/30 * * * ?"  (every 30 min)             │     │
│  │  endCron:   "0 29/30 * * * ?" (closes 29 min later)      │     │
│  └──────────────────────────────────────────────────────────┘     │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────┐     │
│  │  ACTIONS (ordered)                                        │     │
│  │  1. ON_OPEN       → NOTIFY    (ops Slack)                 │     │
│  │  2. ON_CLOSING    → VALIDATE_COMPLETENESS                 │     │
│  │  3. ON_CLOSING    → EVENTS_TO_FILE → SFTP delivery        │     │
│  │  4. ON_CLOSING    → ARCHIVE  → S3 long-term               │     │
│  │  5. ON_EMPTY_CLOSE → NOTIFY  (alert: no events!)          │     │
│  │  6. ON_ERROR      → INVOKE_EXTERNAL → ops PagerDuty       │     │
│  └──────────────────────────────────────────────────────────┘     │
│                                                                    │
│  ┌──────────────────────────────────────────────────────────┐     │
│  │  INTEGRATION CHANNELS                                     │     │
│  │  sftp-acme-prod  ·  s3-archive  ·  slack-ops              │     │
│  └──────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────────┘
```

---

## Profile Domain Model

```kotlin
/**
 * Aggregate root for a complete batch workflow configuration.
 * Version-controlled, hot-reloadable without service restarts.
 */
data class Profile(
    val id: String,                          // e.g. "OUTBOUND_FILE_USER_PROFILE_1"
    val name: String,                        // Human-readable label
    val description: String = "",
    val clientId: String,                    // Owning tenant / client

    val window: WindowConfig,
    val actions: List<Action>,
    val integrations: List<String>,          // Integration IDs used by this profile

    val status: ProfileStatus = ProfileStatus.ENABLED,
    val version: Int = 1,                    // Auto-incremented on each PUT
    val tags: List<String> = emptyList(),    // e.g. ["ach", "settlement", "daily"]

    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: String,
)

enum class ProfileStatus {
    ENABLED,    // Window scheduling active
    DISABLED,   // Window scheduling paused; manual triggers still work
    DRAFT,      // Not yet deployed; safe to edit
    ARCHIVED,   // Retired; read-only
}
```

---

## Integration Channels

Integration Channels define the concrete connections — sources and destinations — that Actions use. They are configured once and reused across multiple Profiles.

### Channel Hierarchy

```kotlin
/**
 * Base interface for all integration channel types.
 * Implementations are discovered via @Component and registered
 * in IntegrationChannelRegistry — no core changes required to add a new type.
 */
interface IntegrationChannel {
    val id: String
    val type: IntegrationChannelType
    val clientId: String
    val credentialRef: String?
    fun testConnectivity(): ConnectivityResult
}

enum class IntegrationChannelType { SFTP, KAFKA, DATABASE, REST_API, S3, GCS, ADLS, MS_TEAMS, SMTP }
```

---

### SFTP Channel

Secure file transfer with SSH — the most common inbound and outbound integration for financial and healthcare batch files.

```kotlin
data class SftpChannel(
    override val id: String,
    override val clientId: String,
    override val credentialRef: String,    // SSH key or password credential

    val host: String,
    val port: Int = 22,
    val username: String,

    // Inbound (File → Events)
    val remoteInboundDirectory: String? = null,
    val filePattern: String = "*.csv",          // Glob pattern
    val pollIntervalSeconds: Int = 60,
    val deleteAfterPickup: Boolean = false,
    val archiveDirectory: String? = null,       // Move to archive after pickup

    // Outbound (Events → File)
    val remoteOutboundDirectory: String? = null,
    val filePermissions: String = "0640",       // Unix octet string

    // Reliability
    val deduplication: DeduplicationConfig = DeduplicationConfig(),
    val checksumValidation: Boolean = true,
    val connectionTimeoutMs: Int = 30_000,
    val maxConcurrentConnections: Int = 5,
    val knownHostsFile: String? = null,

) : IntegrationChannel {
    override val type = IntegrationChannelType.SFTP
}
```

---

### Kafka Channel

High-throughput event streaming — ideal for real-time inbound event collection or fan-out to downstream consumers.

```kotlin
data class KafkaChannel(
    override val id: String,
    override val clientId: String,
    override val credentialRef: String?,   // SASL credential ref (null = no auth)

    val bootstrapServers: List<String>,

    // Consumer (File → Events direction — read from Kafka)
    val consumerTopic: String? = null,
    val consumerGroupId: String? = null,
    val autoOffsetReset: String = "earliest",
    val maxPollRecords: Int = 500,

    // Producer (Events → File direction — publish events to Kafka)
    val producerTopic: String? = null,
    val compressionType: String = "snappy",
    val acks: String = "all",              // Durability: "0", "1", "all"
    val idempotentProducer: Boolean = true,

    // Dead Letter Queue
    val dlqTopic: String? = null,
    val dlqMaxRetries: Int = 3,

    // Security
    val securityProtocol: String = "PLAINTEXT",  // PLAINTEXT, SSL, SASL_SSL, SASL_PLAINTEXT
    val sslTruststoreCredentialRef: String? = null,

) : IntegrationChannel {
    override val type = IntegrationChannelType.KAFKA
}
```

---

### Database Channel

Direct database integration for legacy systems and staging-table patterns.

```kotlin
data class DatabaseChannel(
    override val id: String,
    override val clientId: String,
    override val credentialRef: String,

    val jdbcUrl: String,           // e.g. "jdbc:postgresql://host:5432/db"
    val driverClass: String,       // e.g. "org.postgresql.Driver"

    // Inbound (polling from staging tables)
    val pollQuery: String? = null,           // SELECT ... WHERE processed = false
    val markProcessedQuery: String? = null,  // UPDATE ... SET processed = true
    val pollBatchSize: Int = 1000,

    // Outbound (writing records to DB)
    val insertStatement: String? = null,     // Parameterized INSERT
    val upsertStrategy: UpsertStrategy = UpsertStrategy.INSERT_ONLY,
    val batchWriteSize: Int = 500,
    val transactionSize: Int = 5000,         // Commit every N records

    // Connection pool
    val maxPoolSize: Int = 10,
    val connectionTimeoutMs: Int = 30_000,
    val queryTimeoutMs: Int = 60_000,

) : IntegrationChannel {
    override val type = IntegrationChannelType.DATABASE
}

enum class UpsertStrategy { INSERT_ONLY, UPSERT_ON_KEY, REPLACE }
```

---

### REST API / Webhook Channel

HTTP-based integration for modern APIs, webhooks, and event-driven architectures.

```kotlin
data class RestApiChannel(
    override val id: String,
    override val clientId: String,
    override val credentialRef: String?,   // API key, OAuth2, or null for open

    val baseUrl: String,                   // e.g. "https://api.partner.com/v2"

    // Inbound (polling an API)
    val pollEndpoint: String? = null,      // GET endpoint for polling
    val pollHeaders: Map<String, String> = emptyMap(),
    val paginationStrategy: PaginationStrategy? = null,

    // Outbound (posting data to a webhook / API)
    val webhookEndpoint: String? = null,   // POST endpoint
    val webhookHeaders: Map<String, String> = emptyMap(),
    val webhookBodyTemplate: String? = null, // Handlebars JSON template
    val batchSize: Int = 100,              // Records per request

    // Auth
    val authType: RestAuthType = RestAuthType.BEARER_TOKEN,
    val oauth2Config: OAuth2Config? = null,

    // Reliability
    val timeoutMs: Int = 30_000,
    val retry: RetryConfig = RetryConfig(),
    val rateLimitRps: Int? = null,         // Requests per second cap

) : IntegrationChannel {
    override val type = IntegrationChannelType.REST_API
}

enum class RestAuthType { NONE, BEARER_TOKEN, API_KEY_HEADER, API_KEY_QUERY, BASIC, OAUTH2_CLIENT }
enum class PaginationStrategy { CURSOR, PAGE_NUMBER, OFFSET, LINK_HEADER }

data class OAuth2Config(
    val tokenEndpoint: String,
    val scope: String? = null,
    val tokenCacheSeconds: Int = 3600,
)
```

---

### S3 Channel

Object storage integration — used for archival, large-file delivery, and data lake ingestion.

```kotlin
data class S3Channel(
    override val id: String,
    override val clientId: String,
    override val credentialRef: String,     // AWS access key credential

    val bucketName: String,
    val region: String,
    val endpoint: String? = null,           // null = AWS; set for MinIO / compatible

    // Inbound (pick up files from S3)
    val inboundPrefix: String? = null,      // e.g. "incoming/payments/"
    val filePattern: String = "*.csv",
    val deleteAfterPickup: Boolean = false,

    // Outbound (upload files to S3)
    val outboundPrefix: String? = null,     // e.g. "outbound/processed/"
    val storageClass: String = "STANDARD",  // STANDARD, INTELLIGENT_TIERING, GLACIER
    val serverSideEncryption: String? = "AES256",
    val contentType: String = "application/octet-stream",

    val multipartThresholdMb: Int = 100,    // Use multipart upload above this size

) : IntegrationChannel {
    override val type = IntegrationChannelType.S3
}
```

---

## Complete Profile Example

A full YAML Profile definition — zero code required to deploy this:

```yaml
id: ACH-OUTBOUND-DAILY-ACME
name: "ACME Corp — ACH Daily Outbound"
clientId: acme-corp
description: "Collects payment events hourly, generates NACHA ACH file at 17:00 weekdays"
status: ENABLED
tags: [ach, nacha, settlement, daily]

window:
  id: acme-ach-daily-window
  frequency:
    startCronExpression: "0 0 8 * * MON-FRI ?"     # Opens 08:00 weekdays
    endCronExpression:   "0 0 17 * * MON-FRI ?"    # Closes 17:00 weekdays
    recurringInterval:   PT1H                        # Heartbeat every hour
  autoCreateDefaultWindow: false
  timezone: America/New_York
  deduplication:
    strategy: FIELD_BASED
    fields: [transactionId]

actions:
  - id: notify-window-open
    condition: ON_OPEN
    type: NOTIFY
    executionOrder: 1
    config:
      channel: SLACK
      template: window-opened
      recipients: ["#payments-ops"]
      severity: INFO

  - id: ingest-payments
    condition: RECURRING_WHILE_OPEN
    type: FILE_TO_EVENTS
    executionOrder: 1
    config:
      specId: acme-payments-v2
      integrationId: sftp-acme-prod
      onSuccess: ARCHIVE
      onFailure: QUARANTINE

  - id: validate-count
    condition: ON_CLOSING
    type: VALIDATE_COMPLETENESS
    executionOrder: 1
    config:
      expectedCountSource:
        type: DATABASE_QUERY
        integrationId: db-acme-control
        query: "SELECT expected_count FROM batch_control WHERE date = CURRENT_DATE"
      tolerance:
        percentTolerance: 0.5    # Allow ±0.5%
      onMismatch: BLOCK_AND_NOTIFY

  - id: generate-ach-file
    condition: ON_CLOSING
    type: EVENTS_TO_FILE
    executionOrder: 2
    continueOnFailure: false
    config:
      specId: nacha-ach-outbound
      integrationId: sftp-fed-prod
      emptyFilePolicy: SKIP

  - id: archive-to-s3
    condition: ON_CLOSING
    type: ARCHIVE
    executionOrder: 3
    config:
      integrationId: s3-acme-archive
      pathTemplate: "ach/{year}/{month}/{windowId}.jsonl.gz"
      compression: GZIP
      retentionDays: 2555   # 7 years regulatory retention

  - id: notify-success
    condition: ON_CLOSING
    type: NOTIFY
    executionOrder: 4
    config:
      channel: SLACK
      template: batch-success
      recipients: ["#payments-ops"]

  - id: alert-empty-window
    condition: ON_EMPTY_CLOSE
    type: NOTIFY
    executionOrder: 1
    config:
      channel: SLACK
      template: empty-window-alert
      recipients: ["#payments-ops", "#client-success"]
      severity: WARNING

  - id: page-on-error
    condition: ON_ERROR
    type: INVOKE_EXTERNAL
    executionOrder: 1
    config:
      integrationId: pagerduty-ops
      pathTemplate: "/incidents"
      bodyTemplate: >
        {
          "routing_key": "{{env.PAGERDUTY_KEY}}",
          "event_action": "trigger",
          "payload": {
            "summary": "ACH batch failed: {{profileId}} window {{windowId}}",
            "severity": "critical",
            "source": "transform-platform"
          }
        }

integrations:
  - sftp-acme-prod
  - sftp-fed-prod
  - db-acme-control
  - s3-acme-archive
  - pagerduty-ops
```

---

## Profile Service Interface

```kotlin
interface ProfileService {

    fun createProfile(request: ProfileRequest): Profile
    fun updateProfile(id: String, request: ProfileRequest): Profile
    fun getProfile(id: String): Profile
    fun listProfiles(clientId: String? = null, status: ProfileStatus? = null): List<Profile>
    fun deleteProfile(id: String)

    /** Enable scheduling for a Profile */
    fun enableProfile(id: String): Profile

    /** Pause scheduling without deleting the Profile */
    fun disableProfile(id: String): Profile

    /** Validate a Profile config without saving it */
    fun validateProfile(request: ProfileRequest): ValidationReport

    /** Get full revision history */
    fun getProfileHistory(id: String): List<ProfileRevision>

    /** Restore a previous version */
    fun rollbackProfile(id: String, version: Int): Profile
}

data class ProfileRevision(
    val profileId: String,
    val version: Int,
    val changedBy: String,
    val changedAt: Instant,
    val changeDescription: String,
    val snapshot: Profile,
)

data class ValidationReport(
    val valid: Boolean,
    val errors: List<ValidationError>,
    val warnings: List<String>,
)
```

---

## Version Control and Audit

Every Profile update increments the `version` field and stores a complete `ProfileRevision` snapshot. This enables:

| Capability | How |
|---|---|
| **Rollback** | `POST /api/profiles/{id}/rollback?version=2` |
| **Diff** | Compare any two revision snapshots |
| **Audit trail** | Who changed what and when, with full before/after state |
| **Blue/Green** | Duplicate a Profile with `DRAFT` status, test, then promote |
| **Canary** | Enable a new Profile for a subset of incoming events |

---

## Related Concepts

- **[Window](./window)** — defines the time boundary for event collection
- **[Action](./action)** — the units of work the Profile orchestrates
- **[Zero-Code Onboarding](./zero-code-onboarding)** — how Profiles enable new client setup without deployment
