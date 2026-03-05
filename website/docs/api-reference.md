---
id: api-reference
title: API Reference
sidebar_position: 6
---

# API Reference

The full interactive API is available at `http://localhost:8080/swagger-ui` when running locally.

## Spec Management

### Create a Spec

```http
POST /api/v1/specs
Content-Type: application/json
```

**Request body:**

```json
{
  "name": "Bank Transactions CSV",
  "format": "CSV",
  "hasHeader": true,
  "delimiter": ",",
  "fields": [
    {
      "name": "accountNumber",
      "type": "STRING",
      "columnName": "account_number",
      "sensitive": true
    },
    {
      "name": "amount",
      "type": "DECIMAL",
      "columnName": "amount"
    },
    {
      "name": "transactionDate",
      "type": "DATE",
      "columnName": "date",
      "format": "yyyy-MM-dd"
    },
    {
      "name": "description",
      "type": "STRING",
      "columnName": "description",
      "required": false
    }
  ],
  "correctionRules": [
    {
      "ruleId": "trim-desc",
      "field": "description",
      "correctionType": "TRIM"
    }
  ],
  "validationRules": [
    {
      "ruleId": "amount-positive",
      "field": "amount",
      "ruleType": "MIN_VALUE",
      "value": "0",
      "message": "Amount must be positive",
      "severity": "ERROR"
    }
  ]
}
```

**Response:** `201 Created` with the created spec including its `id`.

### List Specs

```http
GET /api/v1/specs
```

**Response:** `200 OK` — array of all registered specs.

### Get Spec

```http
GET /api/v1/specs/{id}
```

**Response:** `200 OK` — the spec, or `404` if not found.

### Delete Spec

```http
DELETE /api/v1/specs/{id}
```

**Response:** `204 No Content`

---

## Transform Operations

### File → Events (Kafka)

Parse a file and publish each record as a Kafka message.

```http
POST /api/v1/transform/file-to-events
Content-Type: multipart/form-data
```

| Field | Type | Description |
|-------|------|-------------|
| `file` | File | The file to transform |
| `specId` | String (UUID) | ID of the registered `FileSpec` |
| `kafkaTopic` | String | Kafka topic to publish records to |
| `skipInvalidRecords` | Boolean | Skip records with errors (default: `false`) |

**Response:**

```json
{
  "status": "SUCCESS",
  "totalRecords": 1000,
  "processedRecords": 998,
  "failedRecords": 2,
  "skippedRecords": 0,
  "durationMs": 342
}
```

### Validate File (Dry Run)

Parse and validate without writing to any destination.

```http
POST /api/v1/transform/validate
Content-Type: multipart/form-data
```

| Field | Type | Description |
|-------|------|-------------|
| `file` | File | The file to validate |
| `specId` | String (UUID) | ID of the registered `FileSpec` |

**Response:** Same `ProcessingResult` shape, with errors for each invalid record included.

---

## Field Types

| Type | Description | Example value |
|------|-------------|---------------|
| `STRING` | Plain text | `"John Doe"` |
| `INTEGER` | Whole number | `42` |
| `DECIMAL` | Decimal number | `10.50` |
| `DATE` | Date with optional format | `"2024-01-15"` |
| `BOOLEAN` | True/false | `true` |

## Correction Types

| Type | Description |
|------|-------------|
| `TRIM` | Remove leading/trailing whitespace |
| `PAD_LEFT` | Left-pad with a character to a target length |
| `PAD_RIGHT` | Right-pad with a character to a target length |
| `UPPER_CASE` | Convert to uppercase |
| `LOWER_CASE` | Convert to lowercase |
| `REGEX_REPLACE` | Replace regex match with a replacement string |
| `COERCE_DATE` | Parse date with a source format, reformat to target |
| `DEFAULT_IF_NULL` | Replace null/blank with a default value |

## Validation Rule Types

| Type | Description |
|------|-------------|
| `REQUIRED` | Field must be non-null and non-empty |
| `MIN_VALUE` | Numeric value must be ≥ `value` |
| `MAX_VALUE` | Numeric value must be ≤ `value` |
| `REGEX` | Field must match the regex in `value` |
| `MIN_LENGTH` | String length must be ≥ `value` |
| `MAX_LENGTH` | String length must be ≤ `value` |
| `DATE_RANGE` | Date must be within the specified range |
| `ALLOWED_VALUES` | Field must be one of the comma-separated `value` list |

## Severity Levels

| Level | Pipeline behaviour |
|-------|-------------------|
| `WARNING` | Attached to record; never causes skipping |
| `ERROR` | Attached to record; causes skip if `skipInvalidRecords=true` |
| `FATAL` | Record is always skipped; increments `failedRecords` |
