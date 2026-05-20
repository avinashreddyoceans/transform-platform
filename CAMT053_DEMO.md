# CAMT.053 End-to-End Demo

This walkthrough creates a fully spec-driven CAMT.053 parsing pipeline:

1. Create a CAMT.053 FileSpec
2. Create a ServiceIntegration (SFTP source + Kafka sink)
3. Create a Profile linking the spec, with FILE_ARRIVAL close trigger
4. Enable the Profile → window auto-creates in PENDING state
5. Simulate file arrival → window activates → action chain fires
6. Action chain: PARSE_FILE → VALIDATE → NOTIFY (Kafka)

All commands target `http://localhost:8080`.

---

## Step 1 — Create the CAMT.053 FileSpec

CAMT.053 is ISO 20022 `BankToCustomerStatement` XML. Each statement contains one
or more `Ntry` (Entry) elements. We define one FieldSpec per XPath-addressable field.

```bash
SPEC_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/specs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "CAMT.053 Bank Statement Entry",
    "description": "ISO 20022 BankToCustomerStatement - Ntry level parser",
    "version": "1.0",
    "format": "ISO20022",
    "encoding": "UTF-8",
    "hasHeader": false,
    "fields": [
      {
        "name": "statementId",
        "displayName": "Statement ID",
        "type": "STRING",
        "path": "/Document/BkToCstmrStmt/Stmt/Id",
        "required": true,
        "description": "Unique statement identifier from the bank"
      },
      {
        "name": "iban",
        "displayName": "IBAN",
        "type": "STRING",
        "path": "/Document/BkToCstmrStmt/Stmt/Acct/Id/IBAN",
        "required": true,
        "description": "IBAN of the account the statement belongs to"
      },
      {
        "name": "currency",
        "displayName": "Currency",
        "type": "STRING",
        "path": "/Document/BkToCstmrStmt/Stmt/Acct/Ccy",
        "required": false,
        "defaultValue": "USD",
        "description": "ISO 4217 currency code for the account"
      },
      {
        "name": "entryRef",
        "displayName": "Entry Reference",
        "type": "STRING",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryRef",
        "required": true,
        "description": "Bank-assigned reference for this entry (debit or credit)"
      },
      {
        "name": "amount",
        "displayName": "Amount",
        "type": "AMOUNT",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/Amt",
        "required": true,
        "scale": 2,
        "description": "Transaction amount"
      },
      {
        "name": "creditDebitIndicator",
        "displayName": "Credit/Debit",
        "type": "ENUM",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/CdtDbtInd",
        "required": true,
        "allowedValues": ["CRDT", "DBIT"],
        "description": "CRDT = credit (money in); DBIT = debit (money out)"
      },
      {
        "name": "bookingDate",
        "displayName": "Booking Date",
        "type": "DATE",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/BookgDt/Dt",
        "required": false,
        "format": "yyyy-MM-dd",
        "description": "Date the entry was booked on the bank ledger"
      },
      {
        "name": "valueDate",
        "displayName": "Value Date",
        "type": "DATE",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/ValDt/Dt",
        "required": false,
        "format": "yyyy-MM-dd",
        "description": "Value date (when funds are available)"
      },
      {
        "name": "bankTxCode",
        "displayName": "Bank Transaction Code",
        "type": "STRING",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/BkTxCd/Domn/Cd",
        "required": false,
        "description": "Bank-specific transaction code domain (e.g. PMNT)"
      },
      {
        "name": "txFamily",
        "displayName": "Transaction Family",
        "type": "STRING",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/BkTxCd/Domn/Fmly/Cd",
        "required": false,
        "description": "Transaction family code (e.g. ICDT = Inward Credit Transfer)"
      },
      {
        "name": "endToEndId",
        "displayName": "End-to-End ID",
        "type": "STRING",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/Refs/EndToEndId",
        "required": false,
        "description": "End-to-end reference from the original payment instruction"
      },
      {
        "name": "remittanceInfo",
        "displayName": "Remittance Information",
        "type": "STRING",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/RmtInf/Ustrd",
        "required": false,
        "maxLength": 140,
        "description": "Unstructured remittance info (payment reference text)"
      },
      {
        "name": "debtorName",
        "displayName": "Debtor Name",
        "type": "STRING",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/RltdPties/Dbtr/Nm",
        "required": false,
        "description": "Name of the debtor party"
      },
      {
        "name": "debtorIban",
        "displayName": "Debtor IBAN",
        "type": "STRING",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/RltdPties/DbtrAcct/Id/IBAN",
        "required": false,
        "sensitive": true,
        "description": "IBAN of the debtor account (masked in logs)"
      },
      {
        "name": "creditorName",
        "displayName": "Creditor Name",
        "type": "STRING",
        "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/RltdPties/Cdtr/Nm",
        "required": false,
        "description": "Name of the creditor party"
      }
    ],
    "validationRules": [
      {
        "ruleId": "vr-001",
        "field": "amount",
        "ruleType": "MIN_VALUE",
        "value": "0",
        "message": "Amount must be non-negative",
        "severity": "ERROR"
      },
      {
        "ruleId": "vr-002",
        "field": "entryRef",
        "ruleType": "NOT_EMPTY",
        "message": "Entry reference must not be empty",
        "severity": "ERROR"
      },
      {
        "ruleId": "vr-003",
        "field": "iban",
        "ruleType": "REGEX",
        "value": "^[A-Z]{2}[0-9]{2}[A-Z0-9]{4}[0-9]{7}([A-Z0-9]?){0,16}$",
        "message": "IBAN format invalid",
        "severity": "WARNING"
      }
    ],
    "correctionRules": [
      {
        "ruleId": "cr-001",
        "field": "remittanceInfo",
        "correctionType": "TRIM",
        "applyOrder": 1
      },
      {
        "ruleId": "cr-002",
        "field": "debtorName",
        "correctionType": "TRIM",
        "applyOrder": 2
      }
    ],
    "metadata": {
      "standard": "ISO 20022",
      "messageType": "camt.053.001.02",
      "namespace": "urn:iso:std:iso:20022:tech:xsd:camt.053.001.02",
      "source": "Goldman Sachs TxB sample"
    }
  }')

echo "$SPEC_RESPONSE" | python3 -m json.tool
SPEC_ID=$(echo "$SPEC_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo ""
echo "✅ FileSpec created: $SPEC_ID"
```

---

## Step 2 — Create Service Integrations

### 2a. SFTP Integration (file source)

```bash
SFTP_RESPONSE=$(curl -s -X POST http://localhost:8080/api/integrations \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Goldman Sachs TxB SFTP",
    "type": "SFTP",
    "details": {
      "host": "sftp.gs.com",
      "port": 22,
      "userName": "acme_client",
      "password": "changeme",
      "inboundDirectory": "/inbound/camt053",
      "filePattern": "camt053_*.xml",
      "pollIntervalSeconds": 60
    }
  }')

SFTP_INTEGRATION_ID=$(echo "$SFTP_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "✅ SFTP Integration created: $SFTP_INTEGRATION_ID"
```

### 2b. Kafka Integration (record sink)

```bash
KAFKA_RESPONSE=$(curl -s -X POST http://localhost:8080/api/integrations \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Kafka - Bank Entries Topic",
    "type": "KAFKA",
    "details": {
      "bootstrapServers": "kafka:9092",
      "topic": "bank.statement.entries",
      "keyField": "entryRef",
      "valueFormat": "JSON"
    }
  }')

KAFKA_INTEGRATION_ID=$(echo "$KAFKA_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "✅ Kafka Integration created: $KAFKA_INTEGRATION_ID"
```

---

## Step 3 — Create the Profile

This profile:
- **Open trigger**: every day at 06:00 UTC (bank files typically arrive early morning)
- **Close trigger**: FILE_ARRIVAL — window closes as soon as the CAMT.053 file lands
- **Action ON_FILE_ARRIVED**: immediately parse and forward the file to Kafka

```bash
PROFILE_RESPONSE=$(curl -s -X POST http://localhost:8080/api/profiles \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"ACME Bank Statement Pipeline\",
    \"clientId\": \"acme-corp\",
    \"description\": \"Daily CAMT.053 bank statement ingestion and Kafka forwarding\",
    \"windowConfig\": {
      \"openTrigger\": {
        \"type\": \"TIME_BASED\",
        \"openCron\": \"0 6 * * *\",
        \"timeZone\": \"UTC\"
      },
      \"closeTrigger\": {
        \"type\": \"FILE_ARRIVAL\",
        \"integrationId\": \"$SFTP_INTEGRATION_ID\",
        \"filePattern\": \"camt053_*.xml\",
        \"pollInterval\": \"PT1M\"
      },
      \"maxOpenDuration\": \"PT18H\",
      \"allowEmptyClose\": false,
      \"lateEventBehaviour\": \"DISCARD\"
    },
    \"actions\": [
      {
        \"name\": \"Parse CAMT.053 and forward to Kafka\",
        \"condition\": \"ON_FILE_ARRIVED\",
        \"executionOrder\": 10,
        \"continueOnFailure\": false,
        \"steps\": [
          {
            \"name\": \"Parse CAMT.053 XML\",
            \"type\": \"PARSE_FILE\",
            \"executionOrder\": 1,
            \"enabled\": true,
            \"config\": {
              \"fileSpecId\": \"$SPEC_ID\",
              \"recordType\": \"CAMT053_ENTRY\",
              \"failOnParseError\": false
            },
            \"retryPolicy\": {
              \"maxAttempts\": 3,
              \"initialDelay\": \"PT1S\",
              \"backoffMultiplier\": 2.0,
              \"maxDelay\": \"PT30S\"
            }
          },
          {
            \"name\": \"Validate entries\",
            \"type\": \"VALIDATE\",
            \"executionOrder\": 2,
            \"enabled\": true,
            \"config\": {
              \"fileSpecId\": \"$SPEC_ID\",
              \"rejectOnError\": false,
              \"reportWarnings\": true
            },
            \"retryPolicy\": {
              \"maxAttempts\": 1
            }
          },
          {
            \"name\": \"Publish to Kafka\",
            \"type\": \"NOTIFY\",
            \"executionOrder\": 3,
            \"enabled\": true,
            \"config\": {
              \"integrationId\": \"$KAFKA_INTEGRATION_ID\",
              \"topic\": \"bank.statement.entries\",
              \"keyField\": \"entryRef\",
              \"includeFields\": [
                \"statementId\", \"iban\", \"currency\",
                \"entryRef\", \"amount\", \"creditDebitIndicator\",
                \"bookingDate\", \"valueDate\", \"bankTxCode\",
                \"endToEndId\", \"remittanceInfo\"
              ]
            },
            \"retryPolicy\": {
              \"maxAttempts\": 5,
              \"initialDelay\": \"PT2S\",
              \"backoffMultiplier\": 2.0,
              \"maxDelay\": \"PT60S\",
              \"retryableErrors\": [\"TimeoutException\", \"IOException\"]
            }
          }
        ]
      },
      {
        \"name\": \"Alert on pipeline failure\",
        \"condition\": \"ON_ERROR\",
        \"executionOrder\": 10,
        \"steps\": [
          {
            \"name\": \"Send failure alert\",
            \"type\": \"NOTIFY\",
            \"executionOrder\": 1,
            \"enabled\": true,
            \"config\": {
              \"channel\": \"WEBHOOK\",
              \"url\": \"https://hooks.slack.com/services/YOUR/WEBHOOK/URL\",
              \"template\": \"CAMT.053 pipeline failed for window {{windowId}}: {{errorMessage}}\"
            },
            \"retryPolicy\": {
              \"maxAttempts\": 3
            }
          }
        ]
      }
    ],
    \"tags\": {
      \"client\": \"acme-corp\",
      \"standard\": \"ISO20022\",
      \"fileType\": \"camt053\",
      \"environment\": \"production\"
    },
    \"createdBy\": \"admin\",
    \"updatedBy\": \"admin\"
  }")

echo "$PROFILE_RESPONSE" | python3 -m json.tool
PROFILE_ID=$(echo "$PROFILE_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo ""
echo "✅ Profile created: $PROFILE_ID"
```

---

## Step 4 — Enable the Profile

Enabling the profile registers the Quartz trigger and creates the first PENDING window.

```bash
curl -s -X POST "http://localhost:8080/api/profiles/${PROFILE_ID}/enable" \
  | python3 -m json.tool
echo ""
echo "✅ Profile enabled — Quartz trigger registered"
```

**What happens automatically:**

1. `WindowSchedulingService` receives the enable event
2. Computes `nextFireTime` from `openCron = "0 6 * * *"` → tomorrow at 06:00 UTC
3. Creates a `WindowInstance` with `status = PENDING`, `scheduledOpenAt = tomorrow 06:00`
4. Persists to `windows` table

Verify the window was created:

```bash
curl -s "http://localhost:8080/api/windows?profileId=${PROFILE_ID}" | python3 -m json.tool
```

Expected response:
```json
[
  {
    "id": "<window-uuid>",
    "profileId": "<profile-id>",
    "status": "PENDING",
    "scheduledOpenAt": "2026-03-23T06:00:00Z",
    "eventCount": 0,
    "fileCount": 0
  }
]
```

---

## Step 5 — Manually trigger the window open (dev shortcut)

In production, the Quartz job opens windows at `scheduledOpenAt`. During development, use
the trigger endpoint to open immediately:

```bash
TRIGGER_RESPONSE=$(curl -s -X POST "http://localhost:8080/api/profiles/${PROFILE_ID}/trigger")
echo "$TRIGGER_RESPONSE" | python3 -m json.tool

WINDOW_ID=$(echo "$TRIGGER_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('windowId', d.get('id', '')))")
echo ""
echo "✅ Window opened manually: $WINDOW_ID"
```

After this call the window status changes to `OPEN`.

---

## Step 6 — Push file via SFTP (simulated)

In production the SFTP listener polls the configured directory and calls
`POST /api/files/inbound` when a new file arrives. You can simulate this directly:

```bash
curl -s -X POST http://localhost:8080/api/files/inbound \
  -H "Content-Type: application/json" \
  -d "{
    \"integrationId\": \"$SFTP_INTEGRATION_ID\",
    \"remoteIdentifier\": \"/inbound/camt053/camt053_20260322.xml\",
    \"fileName\": \"camt053_20260322.xml\",
    \"windowId\": \"$WINDOW_ID\",
    \"profileId\": \"$PROFILE_ID\",
    \"clientId\": \"acme-corp\",
    \"fileSizeBytes\": 24576,
    \"contentChecksum\": \"a3f4b2c1d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2\",
    \"mimeType\": \"application/xml\"
  }" | python3 -m json.tool

echo ""
echo "✅ File arrival recorded in file_log"
```

**What happens automatically after file arrival:**

1. `FileArrivalTriggerEvaluator` detects the file matches `camt053_*.xml` pattern
2. Window close trigger fires: `TriggerResult.FIRE_AND_PURGE`
3. Window status transitions: `OPEN → CLOSING`
4. `ActionChainExecutor` finds actions with `condition = ON_FILE_ARRIVED`
5. Creates a `WorkflowExecution` in `window_action_executions` with `status = PENDING`
6. Quartz picks up the execution and starts running steps

---

## Step 7 — Watch the execution (poll until complete)

```bash
# List executions for this window
curl -s "http://localhost:8080/api/windows/${WINDOW_ID}/executions" | python3 -m json.tool

# Get detailed step-level view
EXEC_ID=$(curl -s "http://localhost:8080/api/windows/${WINDOW_ID}/executions" \
  | python3 -c "import sys,json; execs=json.load(sys.stdin); print(execs[0]['id']) if execs else print('')")

echo "Execution ID: $EXEC_ID"

curl -s "http://localhost:8080/api/executions/${EXEC_ID}" | python3 -m json.tool
```

**Expected execution flow:**

```
Step 1: Parse CAMT.053 XML     → RUNNING → COMPLETED  (N entries parsed)
Step 2: Validate entries       → RUNNING → COMPLETED  (validation report attached)
Step 3: Publish to Kafka       → RUNNING → COMPLETED  (N messages published)
Overall: COMPLETED
```

---

## Step 8 — Verify Kafka output

If you have a Kafka consumer running:

```bash
# Using kafkacat / kcat
kcat -b localhost:9092 -t bank.statement.entries -C -o beginning -c 10 | python3 -m json.tool
```

Expected Kafka message format (one per CAMT.053 `Ntry`):
```json
{
  "statementId": "STMT20260322001",
  "iban": "DE89370400440532013000",
  "currency": "USD",
  "entryRef": "ENTRY-001",
  "amount": 12500.00,
  "creditDebitIndicator": "CRDT",
  "bookingDate": "2026-03-22",
  "valueDate": "2026-03-22",
  "bankTxCode": "PMNT",
  "endToEndId": "E2E-REF-00123",
  "remittanceInfo": "Invoice INV-2026-0042"
}
```

---

## Full end-to-end summary

```
FileSpec (camt053)          Spec-driven parser config
    │
    ▼
Profile (enabled)           Window opens daily @ 06:00 UTC
    │
    ▼
Window (OPEN)               FILE_ARRIVAL trigger armed on SFTP
    │
    ▼ file lands on SFTP
FileLog (INBOUND)           File logged, trigger fires
    │
    ▼
WorkflowExecution           3-step action chain created
    ├─ PARSE_FILE           → XML parsed into CAMT053_ENTRY records (WindowEvents)
    ├─ VALIDATE             → Validation rules from FileSpec applied
    └─ NOTIFY               → Each entry published to Kafka topic
    │
    ▼
Window (CLOSED)             COMPLETED_WITH_ERRORS or COMPLETED
```

---

## Useful admin queries (UI)

| Page | URL | What to look for |
|------|-----|------------------|
| Windows | `/windows` | Window status = CLOSED, eventCount > 0 |
| Window detail | `/windows/<id>` | Data Records tab: CAMT053_ENTRY records |
| Executions | `/executions` | Status = COMPLETED, actionName = "Parse CAMT.053..." |
| Execution detail | `/executions/<id>` | 3 steps all COMPLETED, stepCount records |
| Files | `GET /api/windows/<id>/files` | 1 INBOUND entry for camt053_20260322.xml |
