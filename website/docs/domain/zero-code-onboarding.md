---
title: Zero-Code Onboarding
description: Deploy a complete batch workflow for a new client without writing a single line of code
sidebar_position: 4
---

# Zero-Code Onboarding

The Transform Platform's architecture is specifically designed so that **onboarding a new client, use case, or data flow requires no code changes, no redeployment, and no service restarts**.

Everything is driven by configuration: FileSpecs, Profiles, Windows, Actions, and Integration Channels are all JSON/YAML artifacts that are loaded, validated, and hot-activated via the REST API.

---

## The Five Steps

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        ZERO-CODE ONBOARDING FLOW                            │
└─────────────────────────────────────────────────────────────────────────────┘

  STEP 1          STEP 2           STEP 3           STEP 4          STEP 5
  ┌──────┐        ┌──────┐         ┌──────┐         ┌──────┐        ┌──────┐
  │ POST │        │ POST │         │ POST │          │ POST │        │ POST │
  │/creds│───────►│/integ│────────►│/specs│─────────►│/prof │───────►│Enable│
  └──────┘        └──────┘         └──────┘         └──────┘        └──────┘
  Encrypt &       Register         Define             Wire it        Hot-reload
  store auth      SFTP/Kafka/DB    field layout,      all            scheduling
  credentials     endpoints        rules & mappings   together       starts
```

---

## Step 1: Store Credentials

Store encrypted credentials before registering any integration. Plaintext values are never persisted.

```bash
# SFTP private key
curl -X POST https://api.transform-platform/api/credentials \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "ref": "acme-sftp-prod",
    "type": "SSH_PRIVATE_KEY",
    "values": {
      "privateKey": "-----BEGIN RSA PRIVATE KEY-----\n...",
      "passphrase": ""
    }
  }'

# S3 access key
curl -X POST https://api.transform-platform/api/credentials \
  -d '{
    "ref": "acme-s3-archive",
    "type": "AWS_ACCESS_KEY",
    "values": {
      "accessKeyId": "AKIA...",
      "secretAccessKey": "abc123..."
    }
  }'
```

---

## Step 2: Register Integration Channels

```bash
# SFTP — inbound file pickup
curl -X POST https://api.transform-platform/api/integrations \
  -d '{
    "clientId": "acme-corp",
    "type": "SFTP",
    "name": "ACME Production SFTP",
    "config": {
      "host": "sftp.acme-corp.com",
      "port": 22,
      "username": "transform-svc",
      "remoteInboundDirectory": "/payments/incoming",
      "filePattern": "payments_*.csv",
      "deleteAfterPickup": false,
      "archiveDirectory": "/payments/archive"
    },
    "credentialRef": "acme-sftp-prod"
  }'

# S3 — archive destination
curl -X POST https://api.transform-platform/api/integrations \
  -d '{
    "clientId": "acme-corp",
    "type": "S3",
    "name": "ACME Archive Bucket",
    "config": {
      "bucketName": "acme-transform-archive",
      "region": "us-east-1",
      "outboundPrefix": "processed/payments/",
      "storageClass": "INTELLIGENT_TIERING"
    },
    "credentialRef": "acme-s3-archive"
  }'
```

Test connectivity before proceeding:
```bash
curl -X POST https://api.transform-platform/api/integrations/int-acme-sftp-prod/test
# → { "status": "CONNECTED", "latencyMs": 142, "message": "Listed 3 files" }
```

---

## Step 3: Define FileSpecs

Register the FileSpec that describes the input/output file structure. This is the "contract" between the platform and the data.

```bash
curl -X POST https://api.transform-platform/api/specs \
  -d '{
    "id": "acme-payments-v2",
    "name": "ACME Payments CSV",
    "format": "CSV",
    "delimiter": ",",
    "hasHeader": true,
    "fields": [
      { "name": "transactionId", "index": 0, "type": "STRING", "required": true },
      { "name": "amount",        "index": 1, "type": "DECIMAL", "required": true,
        "validations": [{"type": "RANGE", "min": 0.01, "max": 1000000}] },
      { "name": "currency",      "index": 2, "type": "STRING", "required": true,
        "validations": [{"type": "ALLOWED_VALUES", "values": ["USD","EUR","GBP"]}] },
      { "name": "accountNumber", "index": 3, "type": "STRING", "required": true, "masked": true },
      { "name": "effectiveDate", "index": 4, "type": "DATE",   "required": true }
    ],
    "correctionRules": [
      { "type": "TRIM",      "fields": ["*"],        "applyOrder": 1 },
      { "type": "UPPERCASE", "fields": ["currency"], "applyOrder": 2 }
    ],
    "validationRules": [
      { "type": "NOT_EMPTY", "fields": ["transactionId", "amount", "accountNumber"] }
    ]
  }'
```

For **supported standard formats** (ACH, ISO 20022, NACHA, EDI 820/835), use a pre-built spec template:

```bash
# Load a built-in NACHA ACH template — no field definitions needed
curl -X POST https://api.transform-platform/api/specs/from-template \
  -d '{
    "templateId": "nacha-ach-ccd",
    "id": "acme-ach-outbound",
    "clientId": "acme-corp"
  }'
```

---

## Step 4: Create the Profile

Wire everything together in a Profile — the single configuration artifact that drives the entire workflow:

```bash
curl -X POST https://api.transform-platform/api/profiles \
  -d '{
    "id": "ACME-PAYMENTS-DAILY",
    "name": "ACME Payments Daily Batch",
    "clientId": "acme-corp",
    "status": "DRAFT",

    "window": {
      "id": "acme-payments-window",
      "frequency": {
        "startCronExpression": "0 0 8 * * MON-FRI ?",
        "endCronExpression":   "0 0 17 * * MON-FRI ?"
      },
      "autoCreateDefaultWindow": false,
      "timezone": "America/New_York"
    },

    "actions": [
      {
        "id": "ingest-files",
        "condition": "RECURRING_WHILE_OPEN",
        "type": "FILE_TO_EVENTS",
        "executionOrder": 1,
        "config": {
          "specId": "acme-payments-v2",
          "integrationId": "int-acme-sftp-prod",
          "onSuccess": "ARCHIVE"
        }
      },
      {
        "id": "generate-ach",
        "condition": "ON_CLOSING",
        "type": "EVENTS_TO_FILE",
        "executionOrder": 1,
        "config": {
          "specId": "acme-ach-outbound",
          "integrationId": "int-acme-sftp-prod"
        }
      },
      {
        "id": "archive",
        "condition": "ON_CLOSING",
        "type": "ARCHIVE",
        "executionOrder": 2,
        "config": {
          "integrationId": "int-acme-s3-archive",
          "pathTemplate": "acme/{year}/{month}/{windowId}.jsonl.gz"
        }
      }
    ],

    "integrations": ["int-acme-sftp-prod", "int-acme-s3-archive"]
  }'
```

Validate before enabling:
```bash
curl -X POST https://api.transform-platform/api/profiles/ACME-PAYMENTS-DAILY/validate
# → { "valid": true, "errors": [], "warnings": ["No ON_ERROR action configured"] }
```

---

## Step 5: Enable and Go Live

```bash
# Promote from DRAFT → ENABLED
curl -X POST https://api.transform-platform/api/profiles/ACME-PAYMENTS-DAILY/enable

# The platform immediately:
# 1. Schedules the window cron jobs
# 2. Registers integration channel pollers
# 3. Confirms connectivity to all channels
# 4. → Response: { "status": "ENABLED", "nextWindowOpenAt": "2024-11-18T08:00:00-05:00" }
```

---

## Format Coverage Matrix

The table below shows which standard file formats are supported out-of-the-box with pre-built FileSpec templates, versus formats that require a custom spec definition.

| Format | Spec Template | Notes |
|---|---|---|
| **NACHA ACH** (PPD, CCD, CTX, WEB) | ✅ Built-in | Addenda records, batch headers, file control auto-generated |
| **ISO 20022** (pain.001, pain.002, camt.053) | ✅ Built-in | XSD-validated; namespace-aware parser |
| **EDI 820 / 835** | ✅ Built-in | X12 segment parser, ISA/GS envelope handling |
| **SWIFT MT 103 / 202** | ✅ Built-in | Tag-based fixed-format parser |
| **CSV / TSV** | Custom spec | Define delimiter, hasHeader, field list |
| **Fixed-Width** | Custom spec | Define start/length per field |
| **JSON** | Custom spec | JSONPath field mapping |
| **XML** | Custom spec | XPath field mapping |
| **Parquet** | Custom spec | Schema auto-detected or explicit |

---

## Operational Controls

Once a Profile is live, operators can intervene without touching code or config files:

| Operation | API Call | Effect |
|---|---|---|
| **Pause** | `POST /profiles/{id}/disable` | Stops new windows opening; in-flight windows complete |
| **Resume** | `POST /profiles/{id}/enable` | Re-activates window scheduling |
| **Force close window** | `POST /windows/{instanceId}/close` | Triggers ON_CLOSING actions immediately |
| **Reprocess window** | `POST /windows/{instanceId}/reprocess` | Re-runs all CLOSED window actions |
| **Manual trigger** | `POST /profiles/{id}/trigger?condition=ON_MANUAL_TRIGGER` | Fires ON_MANUAL_TRIGGER actions |
| **Rollback config** | `POST /profiles/{id}/rollback?version=3` | Restores a previous Profile version |
| **Clone profile** | `POST /profiles/{id}/clone` | Creates a DRAFT copy for safe editing |

---

## Related Concepts

- **[Window](./window)** — time-bounded event buckets
- **[Action](./action)** — the units of work a Profile orchestrates
- **[Profile](./profile)** — the aggregate root tying everything together
- **[FileSpecs](../architecture)** — the field-level contracts driving parsing and generation
