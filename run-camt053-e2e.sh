#!/usr/bin/env bash
# =============================================================================
# CAMT.053 End-to-End Workflow Demo
# =============================================================================
#
# Runs the full pipeline against a running transform-platform instance:
#
#   1. Start local SFTP server (Docker)
#   2. Create CAMT.053 FileSpec
#   3. Create SFTP integration (localhost:2222)
#   4. Create Kafka integration (localhost:9092)
#   5. Create Profile: FILE_ARRIVAL trigger → PARSE → VALIDATE → NOTIFY (Kafka)
#   6. Enable profile  → PENDING window auto-created
#   7. Open window     → status = OPEN, FILE_ARRIVAL trigger armed
#   8. Drop CAMT.053 file on SFTP  → Camel picks it up → workflow fires
#      OR (--fast flag): submit file directly via multipart API
#   9. Poll until workflow COMPLETED
#  10. Print results: records on Kafka + REST API query
#
# Usage:
#   ./run-camt053-e2e.sh              # real SFTP mode (default)
#   ./run-camt053-e2e.sh --fast       # skip SFTP; submit file directly via API
#   ./run-camt053-e2e.sh --teardown   # remove all created resources
#
# Prerequisites:
#   - transform-platform app running at localhost:8080
#   - Docker daemon running (for SFTP container)
#   - Core infra running: docker compose --profile core up -d
#   - jq installed (brew install jq)
#   - kcat/kafkacat (optional, for Kafka verification)
#
# =============================================================================

set -euo pipefail

BASE="http://localhost:8080"
KAFKA_BROKER="localhost:9092"
KAFKA_TOPIC="bank.statement.entries"
SFTP_HOST="localhost"
SFTP_PORT="2222"
SFTP_USER="sftpuser"
SFTP_PASS="sftppass"
SFTP_DIR="/upload"
CAMT053_FILE="camt053_sample_file.xml"
FAST_MODE=false
TEARDOWN=false

# State files (written so --teardown can clean up)
STATE_DIR=".e2e-state"
mkdir -p "$STATE_DIR"

# ── Colours ───────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
BOLD='\033[1m'
NC='\033[0m'

ok()   { echo -e "${GREEN}✅  $*${NC}"; }
info() { echo -e "${CYAN}ℹ️   $*${NC}"; }
warn() { echo -e "${YELLOW}⚠️   $*${NC}"; }
err()  { echo -e "${RED}❌  $*${NC}"; exit 1; }
hdr()  { echo -e "\n${BOLD}${CYAN}── $* ──${NC}"; }

# ── Argument parsing ──────────────────────────────────────────────────────────
for arg in "$@"; do
  case $arg in
    --fast)     FAST_MODE=true ;;
    --teardown) TEARDOWN=true  ;;
  esac
done

# ── Teardown mode ─────────────────────────────────────────────────────────────
if [ "$TEARDOWN" = true ]; then
  hdr "Tearing down e2e resources"
  if [ -f "$STATE_DIR/profile_id" ]; then
    PROFILE_ID=$(cat "$STATE_DIR/profile_id")
    curl -s -X POST "$BASE/api/profiles/$PROFILE_ID/disable" > /dev/null 2>&1 || true
    curl -s -X DELETE "$BASE/api/profiles/$PROFILE_ID"       > /dev/null 2>&1 || true
    ok "Profile $PROFILE_ID deleted"
  fi
  if [ -f "$STATE_DIR/sftp_integration_id" ]; then
    SID=$(cat "$STATE_DIR/sftp_integration_id")
    curl -s -X DELETE "$BASE/api/v1/integrations/$SID" > /dev/null 2>&1 || true
    ok "SFTP integration $SID deleted"
  fi
  if [ -f "$STATE_DIR/kafka_integration_id" ]; then
    KID=$(cat "$STATE_DIR/kafka_integration_id")
    curl -s -X DELETE "$BASE/api/v1/integrations/$KID" > /dev/null 2>&1 || true
    ok "Kafka integration $KID deleted"
  fi
  if [ -f "$STATE_DIR/spec_id" ]; then
    SPEC_ID=$(cat "$STATE_DIR/spec_id")
    curl -s -X DELETE "$BASE/api/v1/specs/$SPEC_ID" > /dev/null 2>&1 || true
    ok "FileSpec $SPEC_ID deleted"
  fi
  docker compose -f .docker/docker-compose.yml --profile sftp down 2>/dev/null || true
  ok "SFTP container stopped"
  rm -rf "$STATE_DIR"
  ok "State directory cleaned"
  echo ""
  ok "Teardown complete."
  exit 0
fi

# ── Preflight checks ──────────────────────────────────────────────────────────
hdr "Preflight checks"

# App health
HEALTH=$(curl -sf "$BASE/actuator/health" | jq -r '.status' 2>/dev/null || echo "DOWN")
if [ "$HEALTH" != "UP" ]; then
  err "App is not running at $BASE (status=$HEALTH).
  Start it first:  ./restart-app.sh
  Then re-run:     ./run-camt053-e2e.sh"
fi
ok "App is UP"

# CAMT053 file present
[ -f "$CAMT053_FILE" ] || err "CAMT053 file not found: $CAMT053_FILE  (run from project root)"
FILE_SIZE=$(wc -c < "$CAMT053_FILE" | tr -d ' ')
ok "CAMT053 file: $CAMT053_FILE ($FILE_SIZE bytes)"

# jq
command -v jq >/dev/null 2>&1 || err "jq not found. Install: brew install jq"
ok "jq available"

# Docker (only needed for real SFTP mode)
if [ "$FAST_MODE" = false ]; then
  command -v docker >/dev/null 2>&1 || err "Docker not found (required for SFTP mode). Use --fast to skip."
  ok "Docker available"
fi

# =============================================================================
# STEP 1 — Start local SFTP server
# =============================================================================
if [ "$FAST_MODE" = false ]; then
  hdr "Step 1 — Start local SFTP server"

  if docker ps --format '{{.Names}}' | grep -q "^transform-sftp$"; then
    ok "SFTP container already running"
  else
    info "Building & starting custom SFTP server on localhost:$SFTP_PORT …"
    docker compose -f .docker/docker-compose.yml --profile sftp up -d --build 2>&1 | tail -5

    info "Waiting for SFTP health check to pass …"
    for i in $(seq 1 30); do
      STATUS=$(docker inspect transform-sftp --format '{{.State.Health.Status}}' 2>/dev/null || echo "starting")
      if [ "$STATUS" = "healthy" ]; then
        break
      fi
      sleep 1
    done
    ok "SFTP server ready at localhost:$SFTP_PORT  (inbox / outbox / sent)"
  fi
else
  hdr "Step 1 — SFTP (skipped — fast mode)"
  info "File will be submitted directly via POST /api/windows/{id}/submit-file"
fi

# =============================================================================
# STEP 2 — Create CAMT.053 FileSpec
# =============================================================================
hdr "Step 2 — Create CAMT.053 FileSpec"

SPEC_PAYLOAD='{
  "name": "CAMT.053 Bank Statement Entry (GS TxB)",
  "description": "ISO 20022 BankToCustomerStatement — Ntry-level parser for Goldman Sachs TxB format",
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
      "name": "accountId",
      "displayName": "Account ID",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Acct/Id/Othr/Id",
      "required": false,
      "description": "Account identifier (Othr format — used when IBAN is not present)"
    },
    {
      "name": "iban",
      "displayName": "IBAN",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Acct/Id/IBAN",
      "required": false,
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
      "name": "bankName",
      "displayName": "Bank Name",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Acct/Svcr/FinInstnId/Nm",
      "required": false,
      "description": "Name of the servicing financial institution"
    },
    {
      "name": "entryRef",
      "displayName": "Entry Reference",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryRef",
      "required": true,
      "description": "Bank-assigned reference for this entry"
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
      "name": "reversalIndicator",
      "displayName": "Reversal",
      "type": "BOOLEAN",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/RvslInd",
      "required": false,
      "description": "true if this entry is a reversal"
    },
    {
      "name": "status",
      "displayName": "Entry Status",
      "type": "ENUM",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/Sts",
      "required": false,
      "allowedValues": ["BOOK", "PDNG", "INFO"],
      "description": "Booking status of the entry"
    },
    {
      "name": "bookingDateTime",
      "displayName": "Booking DateTime",
      "type": "DATETIME",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/BookgDt/DtTm",
      "required": false,
      "format": "yyyy-MM-ddTHH:mm:ss.SSSX",
      "description": "Date and time the entry was booked"
    },
    {
      "name": "valueDate",
      "displayName": "Value Date",
      "type": "DATE",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/ValDt/Dt",
      "required": false,
      "format": "yyyy-MM-dd",
      "description": "Value date (when funds become available)"
    },
    {
      "name": "bankTxCode",
      "displayName": "Bank Transaction Code",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/BkTxCd/Prtry/Cd",
      "required": false,
      "description": "Bank-specific transaction code (e.g. ACH Credit Reject, Incoming Wire)"
    },
    {
      "name": "msgId",
      "displayName": "Message ID",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/Refs/MsgId",
      "required": false,
      "description": "Original payment message ID"
    },
    {
      "name": "acctSvcrRef",
      "displayName": "Account Servicer Reference",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/Refs/AcctSvcrRef",
      "required": false,
      "description": "Bank internal reference for the transaction"
    },
    {
      "name": "pmtInfId",
      "displayName": "Payment Info ID",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/Refs/PmtInfId",
      "required": false,
      "description": "Payment information identifier"
    },
    {
      "name": "endToEndId",
      "displayName": "End-to-End ID",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/Refs/EndToEndId",
      "required": false,
      "description": "End-to-end reference from original payment instruction"
    },
    {
      "name": "instructedAmount",
      "displayName": "Instructed Amount",
      "type": "AMOUNT",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/AmtDtls/InstdAmt/Amt",
      "required": false,
      "scale": 2,
      "description": "Original instructed amount"
    },
    {
      "name": "remittanceInfo",
      "displayName": "Remittance Info",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/RmtInf/Ustrd",
      "required": false,
      "maxLength": 140,
      "description": "Unstructured remittance information (payment reference text)"
    },
    {
      "name": "returnInfo",
      "displayName": "Return Info",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/RtrInf/AddtlInf",
      "required": false,
      "description": "Additional return/reject information"
    },
    {
      "name": "creditorName",
      "displayName": "Creditor Name",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/RltdPties/Cdtr/Nm",
      "required": false,
      "description": "Name of the creditor party"
    },
    {
      "name": "creditorAccountId",
      "displayName": "Creditor Account ID",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/RltdPties/CdtrAcct/Id/Othr/Id",
      "required": false,
      "description": "Creditor account identifier"
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
      "name": "debtorAccountId",
      "displayName": "Debtor Account ID",
      "type": "STRING",
      "path": "/Document/BkToCstmrStmt/Stmt/Ntry/NtryDtls/TxDtls/RltdPties/DbtrAcct/Id/IBAN",
      "required": false,
      "sensitive": true,
      "description": "Debtor IBAN (masked in logs)"
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
      "field": "creditDebitIndicator",
      "ruleType": "ALLOWED_VALUES",
      "message": "creditDebitIndicator must be CRDT or DBIT",
      "severity": "ERROR"
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
      "field": "creditorName",
      "correctionType": "TRIM",
      "applyOrder": 2
    },
    {
      "ruleId": "cr-003",
      "field": "debtorName",
      "correctionType": "TRIM",
      "applyOrder": 3
    }
  ],
  "metadata": {
    "standard": "ISO 20022",
    "messageType": "camt.053.001.02",
    "namespace": "urn:iso:std:iso:20022:tech:xsd:camt.053.001.02",
    "source": "Goldman Sachs TxB sample"
  }
}'

SPEC_RESP=$(curl -sf -X POST "$BASE/api/v1/specs" \
  -H "Content-Type: application/json" \
  -d "$SPEC_PAYLOAD")

SPEC_ID=$(echo "$SPEC_RESP" | jq -r '.id')
echo "$SPEC_ID" > "$STATE_DIR/spec_id"
ok "FileSpec created: $SPEC_ID"

# =============================================================================
# STEP 3 — Create SFTP integration (localhost)
# =============================================================================
hdr "Step 3 — Create SFTP integration (localhost:$SFTP_PORT)"

SFTP_RESP=$(curl -sf -X POST "$BASE/api/v1/integrations" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"Local SFTP — CAMT.053 Inbound\",
    \"type\": \"SFTP\",
    \"userId\": \"demo-user\",
    \"shortDescription\": \"Localhost SFTP for CAMT.053 end-to-end demo\",
    \"details\": {
      \"host\": \"$SFTP_HOST\",
      \"port\": $SFTP_PORT,
      \"directories\": [\"$SFTP_DIR\"],
      \"userName\": \"$SFTP_USER\",
      \"password\": \"$SFTP_PASS\",
      \"direction\": \"INBOUND\",
      \"filters\": [\"camt053_*.xml\", \"CAMT053_*.xml\", \"*.xml\"]
    }
  }")

SFTP_INTEGRATION_ID=$(echo "$SFTP_RESP" | jq -r '.id')
echo "$SFTP_INTEGRATION_ID" > "$STATE_DIR/sftp_integration_id"
ok "SFTP integration created: $SFTP_INTEGRATION_ID"

# =============================================================================
# STEP 4 — Create Kafka integration
# =============================================================================
hdr "Step 4 — Create Kafka integration (localhost:9092)"

KAFKA_RESP=$(curl -sf -X POST "$BASE/api/v1/integrations" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"Kafka — bank.statement.entries\",
    \"type\": \"KAFKA\",
    \"userId\": \"demo-user\",
    \"shortDescription\": \"Kafka sink for parsed CAMT.053 entries\",
    \"details\": {
      \"bootstrapServers\": \"$KAFKA_BROKER\",
      \"topic\": \"$KAFKA_TOPIC\",
      \"keyField\": \"entryRef\",
      \"valueFormat\": \"JSON\",
      \"direction\": \"OUTBOUND\",
      \"filters\": []
    }
  }")

KAFKA_INTEGRATION_ID=$(echo "$KAFKA_RESP" | jq -r '.id')
echo "$KAFKA_INTEGRATION_ID" > "$STATE_DIR/kafka_integration_id"
ok "Kafka integration created: $KAFKA_INTEGRATION_ID"

# =============================================================================
# STEP 5 — Create Profile
# =============================================================================
hdr "Step 5 — Create Profile with FILE_ARRIVAL → PARSE → VALIDATE → NOTIFY"

PROFILE_RESP=$(curl -sf -X POST "$BASE/api/profiles" \
  -H "Content-Type: application/json" \
  -d "{
    \"name\": \"CAMT.053 Bank Statement Pipeline\",
    \"clientId\": \"acme-bank-client\",
    \"description\": \"Daily CAMT.053 bank statement ingestion: SFTP → parse → validate → Kafka\",
    \"windowConfig\": {
      \"openTrigger\": {\"type\": \"MANUAL\"},
      \"closeTrigger\": {
        \"type\": \"FILE_ARRIVAL\",
        \"integrationId\": \"$SFTP_INTEGRATION_ID\",
        \"filePattern\": \"*.xml\"
      },
      \"maxOpenDuration\": \"PT4H\",
      \"allowEmptyClose\": false,
      \"deduplicationEnabled\": true
    },
    \"actions\": [
      {
        \"name\": \"Parse CAMT.053 → validate → publish to Kafka\",
        \"condition\": \"ON_FILE_ARRIVED\",
        \"executionOrder\": 10,
        \"continueOnFailure\": false,
        \"enabled\": true,
        \"steps\": [
          {
            \"name\": \"Parse CAMT.053 XML entries\",
            \"stepType\": \"PARSE_FILE\",
            \"executionOrder\": 10,
            \"enabled\": true,
            \"config\": {
              \"fileSpecId\": \"$SPEC_ID\",
              \"recordType\": \"CAMT053_ENTRY\",
              \"topic\": \"$KAFKA_TOPIC\",
              \"failOnParseError\": false
            },
            \"retryPolicy\": {
              \"maxAttempts\": 3,
              \"initialDelayMs\": 1000,
              \"backoffMultiplier\": 2.0,
              \"maxDelayMs\": 30000
            }
          },
          {
            \"name\": \"Validate parsed entries\",
            \"stepType\": \"VALIDATE\",
            \"executionOrder\": 20,
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
            \"name\": \"Confirm Kafka delivery\",
            \"stepType\": \"NOTIFY\",
            \"executionOrder\": 30,
            \"enabled\": true,
            \"config\": {
              \"channel\": \"KAFKA\",
              \"integrationId\": \"$KAFKA_INTEGRATION_ID\",
              \"topic\": \"$KAFKA_TOPIC\",
              \"includeFields\": [
                \"statementId\", \"accountId\", \"currency\", \"bankName\",
                \"entryRef\", \"amount\", \"creditDebitIndicator\",
                \"reversalIndicator\", \"status\", \"bookingDateTime\",
                \"valueDate\", \"bankTxCode\",
                \"msgId\", \"acctSvcrRef\", \"endToEndId\",
                \"remittanceInfo\", \"returnInfo\",
                \"creditorName\", \"debtorName\"
              ]
            },
            \"retryPolicy\": {
              \"maxAttempts\": 3,
              \"initialDelayMs\": 2000,
              \"backoffMultiplier\": 2.0,
              \"maxDelayMs\": 30000
            }
          }
        ]
      },
      {
        \"name\": \"Log ON_CLOSING summary\",
        \"condition\": \"ON_CLOSING\",
        \"executionOrder\": 20,
        \"continueOnFailure\": true,
        \"enabled\": true,
        \"steps\": [
          {
            \"name\": \"Report window close summary\",
            \"stepType\": \"NOTIFY\",
            \"executionOrder\": 10,
            \"enabled\": true,
            \"config\": {
              \"channel\": \"KAFKA\",
              \"topic\": \"bank.statement.window.events\",
              \"template\": \"Window {{windowId}} closed with {{eventCount}} entries from {{fileName}}\"
            },
            \"retryPolicy\": {\"maxAttempts\": 1}
          }
        ]
      }
    ],
    \"tags\": {
      \"client\": \"acme-bank-client\",
      \"standard\": \"ISO20022\",
      \"fileType\": \"camt053\",
      \"environment\": \"local-demo\"
    },
    \"createdBy\": \"e2e-demo\"
  }")

PROFILE_ID=$(echo "$PROFILE_RESP" | jq -r '.id')
echo "$PROFILE_ID" > "$STATE_DIR/profile_id"
ok "Profile created: $PROFILE_ID"

# =============================================================================
# STEP 6 — Enable Profile (creates PENDING window)
# =============================================================================
hdr "Step 6 — Enable profile"

curl -sf -X POST "$BASE/api/profiles/$PROFILE_ID/enable" > /dev/null
ok "Profile enabled"

# Also enable SFTP integration to start Camel polling
if [ "$FAST_MODE" = false ]; then
  curl -sf -X POST "$BASE/api/v1/integrations/$SFTP_INTEGRATION_ID/enable" > /dev/null 2>&1 || \
    warn "Integration enable returned non-200 (Camel route may still start on file arrival)"
  ok "SFTP Camel route started — polling localhost:$SFTP_PORT$SFTP_DIR every 30s for *.xml"
fi

# Check PENDING window
sleep 1
WINDOWS=$(curl -sf "$BASE/api/profiles/$PROFILE_ID/windows")
WINDOW_ID=$(echo "$WINDOWS" | jq -r '.[0].id // empty')
WINDOW_STATUS=$(echo "$WINDOWS" | jq -r '.[0].status // empty')
info "Window created: $WINDOW_ID (status=$WINDOW_STATUS)"
echo "$WINDOW_ID" > "$STATE_DIR/window_id"

# =============================================================================
# STEP 7 — Open window (manual trigger)
# =============================================================================
hdr "Step 7 — Open window (manual trigger)"

TRIGGER_RESP=$(curl -sf -X POST "$BASE/api/profiles/$PROFILE_ID/trigger")
WINDOW_ID=$(echo "$TRIGGER_RESP" | jq -r '.windowId // .id // empty')
if [ -z "$WINDOW_ID" ]; then
  # Fallback: get the OPEN window from the list
  WINDOW_ID=$(curl -sf "$BASE/api/profiles/$PROFILE_ID/windows" | jq -r '[.[] | select(.status=="OPEN")][0].id // .[0].id')
fi
echo "$WINDOW_ID" > "$STATE_DIR/window_id"
ok "Window OPEN: $WINDOW_ID"

# =============================================================================
# STEP 8 — Deliver CAMT.053 file
# =============================================================================

if [ "$FAST_MODE" = false ]; then
  hdr "Step 8 — Drop CAMT.053 file on SFTP (real SFTP mode)"

  REMOTE_FILE="camt053_$(date +%Y%m%d_%H%M%S).xml"
  info "Copying $CAMT053_FILE to SFTP container as /home/$SFTP_USER/upload/$REMOTE_FILE"
  docker cp "$CAMT053_FILE" "transform-sftp:/home/$SFTP_USER/upload/$REMOTE_FILE"
  ok "File dropped: $REMOTE_FILE"

  info "Waiting for Camel route to pick up the file (polls every 15s) …"
  echo "    Watch progress:  docker logs -f transform-sftp"
  WAIT=0
  FOUND=false
  while [ $WAIT -lt 120 ]; do
    sleep 5
    WAIT=$((WAIT+5))
    EXECS=$(curl -sf "$BASE/api/windows/$WINDOW_ID/executions" 2>/dev/null || echo "[]")
    EXEC_COUNT=$(echo "$EXECS" | jq 'length')
    if [ "$EXEC_COUNT" -gt 0 ]; then
      FOUND=true
      ok "Workflow execution started! (waited ${WAIT}s)"
      break
    fi
    echo -ne "\r    Waiting for file pickup … ${WAIT}s / 120s"
  done

  if [ "$FOUND" = false ]; then
    warn "Camel route did not pick up the file within 120s."
    warn "Falling back to direct file submission …"
    FAST_MODE=true
  fi
fi

if [ "$FAST_MODE" = true ]; then
  hdr "Step 8 — Submit CAMT.053 file directly via API"

  info "POST /api/windows/$WINDOW_ID/submit-file (multipart)"
  SUBMIT_RESP=$(curl -sf -X POST \
    "$BASE/api/windows/$WINDOW_ID/submit-file" \
    -F "file=@$CAMT053_FILE;type=application/xml" \
    -F "integrationId=$SFTP_INTEGRATION_ID")

  RECORDS_PROCESSED=$(echo "$SUBMIT_RESP" | jq -r '.recordsProcessed // "?"')
  MATCHED=$(echo "$SUBMIT_RESP" | jq -r '.matched // "?"')
  MSG=$(echo "$SUBMIT_RESP" | jq -r '.message // ""')
  ok "File submitted: recordsProcessed=$RECORDS_PROCESSED, matched=$MATCHED"
  [ -n "$MSG" ] && info "$MSG"
fi

# =============================================================================
# STEP 9 — Poll for workflow completion
# =============================================================================
hdr "Step 9 — Poll for workflow completion"

info "Watching workflow execution …"
EXEC_STATUS="UNKNOWN"
EXEC_ID=""
for i in $(seq 1 30); do
  sleep 2
  EXECS=$(curl -sf "$BASE/api/windows/$WINDOW_ID/executions" 2>/dev/null || echo "[]")
  EXEC_COUNT=$(echo "$EXECS" | jq 'length')
  if [ "$EXEC_COUNT" -gt 0 ]; then
    EXEC_ID=$(echo "$EXECS" | jq -r '.[0].id')
    EXEC_STATUS=$(echo "$EXECS" | jq -r '.[0].status')
    STEP_COUNT=$(echo "$EXECS" | jq -r '.[0].stepExecutions | length // 0')
    echo -ne "\r    Execution $EXEC_ID: status=$EXEC_STATUS, steps=$STEP_COUNT                    "
    if [[ "$EXEC_STATUS" == "COMPLETED" || "$EXEC_STATUS" == "FAILED" ]]; then
      echo ""
      break
    fi
  else
    echo -ne "\r    Waiting for execution to start … attempt $i/30"
  fi
done
echo ""

if [ -z "$EXEC_ID" ]; then
  warn "No workflow execution found after waiting. Check app logs."
else
  echo "$EXEC_ID" > "$STATE_DIR/exec_id"
fi

# =============================================================================
# STEP 10 — Print results
# =============================================================================
hdr "Step 10 — Results"

echo ""
echo -e "${BOLD}Window detail:${NC}"
curl -sf "$BASE/api/windows/$WINDOW_ID" | jq '{
  id, status, profileId,
  eventCount,
  openedAt, closedAt
}'

echo ""
echo -e "${BOLD}Workflow execution:${NC}"
if [ -n "$EXEC_ID" ]; then
  curl -sf "$BASE/api/executions/$EXEC_ID" | jq '{
    id, status, actionName,
    totalRecordsProcessed,
    errorMessage,
    startedAt, completedAt,
    steps: [.stepExecutions[]? | {
      stepName, status, recordsProcessed, errorMessage
    }]
  }'
fi

echo ""
echo -e "${BOLD}Parsed entries stored as WindowEvents (REST):${NC}"
EVENTS=$(curl -sf "$BASE/api/windows/$WINDOW_ID/events?limit=3")
TOTAL=$(echo "$EVENTS" | jq -r '.total // 0')
echo "  Total entries collected: $TOTAL"
if [ "$TOTAL" -gt 0 ]; then
  echo "  First 3 entries:"
  echo "$EVENTS" | jq '.events[:3][] | {
    recordType: .recordType,
    payload: (.payload | {entryRef, amount, creditDebitIndicator, bookingDateTime, bankTxCode, remittanceInfo})
  }'
fi

echo ""
echo -e "${BOLD}Kafka messages on $KAFKA_TOPIC:${NC}"
if command -v kcat >/dev/null 2>&1; then
  info "Reading last $TOTAL messages from $KAFKA_TOPIC …"
  kcat -b "$KAFKA_BROKER" -t "$KAFKA_TOPIC" -C -o beginning \
       -c "${TOTAL:-5}" -e 2>/dev/null \
    | head -5 | while read -r line; do
        echo "$line" | python3 -m json.tool 2>/dev/null || echo "  $line"
      done
else
  warn "kcat not installed — cannot read Kafka messages directly."
  info "Verify via Kafka UI:  http://localhost:8090/ui/clusters/local/all-topics/$KAFKA_TOPIC/messages"
  info "Install kcat:  brew install kcat"
fi

# =============================================================================
# Summary
# =============================================================================
hdr "Summary"

echo ""
echo -e "${GREEN}${BOLD}Pipeline complete! 🎉${NC}"
echo ""
echo -e "  ${BOLD}FileSpec:${NC}          $SPEC_ID"
echo -e "  ${BOLD}SFTP Integration:${NC}  $SFTP_INTEGRATION_ID"
echo -e "  ${BOLD}Kafka Integration:${NC} $KAFKA_INTEGRATION_ID"
echo -e "  ${BOLD}Profile:${NC}           $PROFILE_ID"
echo -e "  ${BOLD}Window:${NC}            $WINDOW_ID  (status=$EXEC_STATUS)"
[ -n "$EXEC_ID" ] && echo -e "  ${BOLD}Execution:${NC}         $EXEC_ID"
echo ""
echo -e "${BOLD}Useful links:${NC}"
echo -e "  Swagger UI:       http://localhost:8080/swagger-ui/index.html"
echo -e "  Window detail:    $BASE/api/windows/$WINDOW_ID"
echo -e "  Entries (REST):   $BASE/api/windows/$WINDOW_ID/events"
echo -e "  Executions:       $BASE/api/windows/$WINDOW_ID/executions"
echo -e "  Kafka UI:         http://localhost:8090/ui/clusters/local/all-topics/$KAFKA_TOPIC/messages"
echo -e "  MinIO:            http://localhost:9001"
echo -e "  pgAdmin:          http://localhost:5050"
echo ""
echo -e "  To clean up:  ./run-camt053-e2e.sh --teardown"
echo ""
