-- ============================================================================
-- Full Schema — Transform Platform
--
-- Tables (in FK dependency order):
--   service_integrations      — SFTP / FTP / S3 / Kafka connector registrations
--   downloaded_files          — integration-layer SFTP/S3 download tracking (refs service_integrations)
--   profiles                  — profile configuration (static, versioned)
--   windows                   — one row per scheduled window execution (refs profiles)
--   file_log                  — bidirectional file ledger (refs windows)
--   window_events             — collected events / parsed records keyed by window_id (refs windows)
--   file_specs                — file format specifications (drives parsing + generation)
--   window_action_executions  — one row per Action execution per Window (refs windows + profiles)
--   workflow_step_executions  — one row per step attempt within an action execution (refs window_action_executions)
-- ============================================================================

-- ── service_integrations ─────────────────────────────────────────────────────
-- One row per onboarded integration (SFTP / FTP / S3 / Kafka).
-- encrypted_details holds an AES-256 encrypted JSON blob whose shape depends on type:
--   SFTP  → SftpDetails  (host, port, directories, userName, password, direction, filters)
--   FTP   → FtpDetails   (same as SFTP)
--   S3    → S3Details    (bucketName, region, accessKeyId, secretAccessKey, prefix)
--   KAFKA → KafkaDetails (bootstrapServers, topic, groupId, securityProtocol)



CREATE TABLE IF NOT EXISTS service_integrations (
    id                  VARCHAR(255)  PRIMARY KEY,
    type                VARCHAR(50)   NOT NULL,
    user_id             VARCHAR(255)  NOT NULL,
    short_description   TEXT          NOT NULL,
    is_enabled          BOOLEAN       NOT NULL DEFAULT false,
    encrypted_details   TEXT          NOT NULL,
    updated_by          VARCHAR(255)  NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             INT           NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_si_type       ON service_integrations (type);
CREATE INDEX IF NOT EXISTS idx_si_user_id    ON service_integrations (user_id);
CREATE INDEX IF NOT EXISTS idx_si_is_enabled ON service_integrations (is_enabled);

-- ── downloaded_files ──────────────────────────────────────────────────────────
-- Operational tracking table used by platform-integration's SFTP/S3 listeners.
-- Each row represents one file downloaded to MinIO and waiting for the pipeline.
--
-- Distinct from file_log (the business ledger) — downloaded_files is the
-- transactional state machine for the integration layer:
--   PENDING    → file is downloaded, waiting to be picked up by action scheduler
--   PROCESSING → a worker has claimed the file
--   PROCESSED  → fully handled; the corresponding file_log entry was created
--   FAILED     → processing failed after all retries

CREATE TABLE IF NOT EXISTS downloaded_files (
    id                      VARCHAR(255)  PRIMARY KEY,
    integration_id          VARCHAR(255)  NOT NULL
                                REFERENCES service_integrations (id) ON DELETE CASCADE,
    remote_file_name        VARCHAR(512)  NOT NULL,
    remote_file_path        VARCHAR(1024),
    file_size_bytes         BIGINT,
    remote_last_modified    TIMESTAMPTZ,
    md5_checksum            VARCHAR(64),
    storage_bucket          VARCHAR(255)  NOT NULL,
    storage_key             VARCHAR(1024) NOT NULL,
    processing_status       VARCHAR(50)   NOT NULL DEFAULT 'PENDING',
    error_message           TEXT,
    downloaded_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processing_started_at   TIMESTAMPTZ,
    processed_at            TIMESTAMPTZ,
    version                 INT           NOT NULL DEFAULT 1,
    CONSTRAINT uq_download_per_integration UNIQUE (integration_id, remote_file_name)
);

CREATE INDEX IF NOT EXISTS idx_df_integration    ON downloaded_files (integration_id);
CREATE INDEX IF NOT EXISTS idx_df_status         ON downloaded_files (processing_status);
CREATE INDEX IF NOT EXISTS idx_df_downloaded     ON downloaded_files (downloaded_at DESC);

-- ── profiles ─────────────────────────────────────────────────────────────────
-- Configuration aggregate. Only written when a user changes the configuration.
-- No runtime scheduling fields here (scheduledOpenAt lives on windows).
--
-- Naming note: PK is `id` here; FK columns in other tables are named `profile_id`
-- (standard SQL FK naming convention — same value, different column name by context).
-- `short_description` is the human-readable profile name shown in the UI.
--
-- window_config and actions stored as JSONB:
--   window_config → WindowConfig (trigger hierarchy, overlap policy, time semantics)
--   actions       → List<Action> (ordered workflow steps per lifecycle condition)

CREATE TABLE IF NOT EXISTS profiles (
    id                  VARCHAR(255) PRIMARY KEY,
    short_description   VARCHAR(255) NOT NULL,
    client_id           VARCHAR(255) NOT NULL,
    description         TEXT         NOT NULL DEFAULT '',
    status              VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    version             INT          NOT NULL DEFAULT 1,
    window_config       JSONB        NOT NULL DEFAULT '{}',
    actions             JSONB        NOT NULL DEFAULT '[]',
    tags                JSONB        NOT NULL DEFAULT '{}',
    created_by          VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by          VARCHAR(255) NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_profile_desc_client UNIQUE (short_description, client_id)
);

CREATE INDEX IF NOT EXISTS idx_profiles_client_id ON profiles (client_id);
CREATE INDEX IF NOT EXISTS idx_profiles_status    ON profiles (status);
CREATE INDEX IF NOT EXISTS idx_profiles_updated   ON profiles (updated_at DESC);

-- ── windows ──────────────────────────────────────────────────────────────────
-- Runtime scheduling bucket. One row per execution cycle.
--
-- PK is `window_id` (instead of `id`) so FK columns in child tables share the
-- same name — aids readability in JOIN conditions.
--
-- arrived_file_handles JSONB:
--   Write-through cache of List<FileHandle> (filename, path, size, checksum).
--   Written on every FILE_ARRIVED event. The PARSE_FILE step executor reads this
--   to open the file without querying window_events (avoids hot-path JOIN).
--
-- close_trigger_state JSONB:
--   Compound trigger checkpoint — tracks which sub-triggers have fired.
--   Persisted on every sub-trigger fire; crash-safe (Flink savepoint analogy).

CREATE TABLE IF NOT EXISTS windows (
    window_id             VARCHAR(255) PRIMARY KEY,
    profile_id            VARCHAR(255) NOT NULL REFERENCES profiles (id),
    profile_version       INT          NOT NULL,
    status                VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    scheduled_open_at     TIMESTAMPTZ,
    scheduled_close_at    TIMESTAMPTZ,
    opened_at             TIMESTAMPTZ,
    closing_started_at    TIMESTAMPTZ,
    closed_at             TIMESTAMPTZ,
    status_reason         TEXT,
    event_count           INT          NOT NULL DEFAULT 0,
    arrived_file_handles  JSONB        NOT NULL DEFAULT '[]',
    close_trigger_state   JSONB        NOT NULL DEFAULT '{"fired":{},"firedAt":{}}',
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_windows_pending_open
    ON windows (scheduled_open_at)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_windows_open_close
    ON windows (scheduled_close_at)
    WHERE status = 'OPEN';

CREATE INDEX IF NOT EXISTS idx_windows_profile_status ON windows (profile_id, status);
CREATE INDEX IF NOT EXISTS idx_windows_status         ON windows (status);
CREATE INDEX IF NOT EXISTS idx_windows_updated        ON windows (updated_at DESC);

-- ── file_log ─────────────────────────────────────────────────────────────────
-- Bidirectional file ledger.
--
--   INBOUND  — files received from external systems (SFTP listener, API upload).
--              window_id links to the window that received the file.
--              Idempotency guard (partial unique index below) prevents duplicate
--              ingestion when the SFTP poller restarts or a file is re-delivered.
--
--   OUTBOUND — files produced by GENERATE_FILE workflow steps.
--              generated_by_execution_id provides full lineage back to the
--              window_action_execution that created the file.
--
-- File content is never stored here — this is metadata only.
-- remote_identifier is the source-system path/key/ID (SFTP path, S3 key, etc.)

CREATE TABLE IF NOT EXISTS file_log (
    id                          VARCHAR(255)  PRIMARY KEY,
    direction                   VARCHAR(20)   NOT NULL DEFAULT 'INBOUND',

    -- Source integration (nullable for API-ingested files)
    integration_id              VARCHAR(255),
    -- Source-system path or identifier (SFTP remote path, S3 key, message ID, etc.)
    remote_identifier           VARCHAR(1024),

    -- Linkage
    window_id                   VARCHAR(255)
                                    REFERENCES windows (window_id) ON DELETE SET NULL,
    profile_id                  VARCHAR(255),
    client_id                   VARCHAR(255),
    generated_by_execution_id   VARCHAR(255),

    -- File metadata
    file_name                   VARCHAR(512)  NOT NULL,
    file_size_bytes             BIGINT,
    content_checksum            VARCHAR(64),          -- SHA-256 of file content
    mime_type                   VARCHAR(128),

    -- Processing state
    status                      VARCHAR(50)   NOT NULL DEFAULT 'RECEIVED',
    error_message               TEXT,

    -- Timestamps
    arrived_at                  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processed_at                TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_fl_direction    ON file_log (direction);
CREATE INDEX IF NOT EXISTS idx_fl_status       ON file_log (status);
CREATE INDEX IF NOT EXISTS idx_fl_window       ON file_log (window_id);
CREATE INDEX IF NOT EXISTS idx_fl_profile      ON file_log (profile_id);
CREATE INDEX IF NOT EXISTS idx_fl_integration  ON file_log (integration_id);
CREATE INDEX IF NOT EXISTS idx_fl_arrived      ON file_log (arrived_at DESC);
CREATE INDEX IF NOT EXISTS idx_fl_execution    ON file_log (generated_by_execution_id);

-- Inbound deduplication: same file from same integration in same window only once.
-- Partial index only applies to INBOUND rows where all three keys are present.
CREATE UNIQUE INDEX IF NOT EXISTS idx_fl_dedup_inbound
    ON file_log (window_id, integration_id, remote_identifier)
    WHERE direction = 'INBOUND'
      AND window_id IS NOT NULL
      AND integration_id IS NOT NULL
      AND remote_identifier IS NOT NULL;

-- ── window_events ─────────────────────────────────────────────────────────────
-- One row per collected event / data record (renamed from window_data).
-- The window is the bucket — every row is owned by exactly one window.
--
-- record_type (discriminator — what kind of event this row represents):
--   RAW_EVENT        — inbound event received via HTTP or Kafka before parsing
--   FILE_ARRIVED     — a file landed on a watched integration during this window
--   PARSED_RECORD    — fully parsed domain record (output of PARSE_FILE step)
--   TRANSFORMED      — record after TRANSFORM_RECORD step
--   DEDUPLICATED     — record that survived the DEDUPLICATE step
--   NOTIFICATION     — record mapped by MAP_TO_NOTIFICATION step
--   GENERATED_FILE_REF — reference to a file produced by GENERATE_FILE step
--
-- source_integration_id (non-FK audit column):
--   Identifies which integration (SFTP/S3/Kafka connector) this event came from.
--   NULL for internally generated records (DEDUPLICATED, NOTIFICATION, etc.).
--   Not a hard FK so the integration can be deleted without losing the audit trail.
--   Enables per-partner volume analytics: GROUP BY source_integration_id.
--
-- At window close time the ON_CLOSING action chain queries:
--   SELECT * FROM window_events WHERE window_id = ? AND record_type = 'PARSED_RECORD'

CREATE TABLE IF NOT EXISTS window_events (
    event_id              VARCHAR(255) PRIMARY KEY,
    window_id             VARCHAR(255) NOT NULL REFERENCES windows (window_id),
    profile_id            VARCHAR(255) NOT NULL,
    client_id             VARCHAR(255) NOT NULL,
    record_type           VARCHAR(50)  NOT NULL,
    source_integration_id VARCHAR(255),
    deduplication_key     VARCHAR(512),
    event_timestamp       TIMESTAMPTZ,
    payload               JSONB        NOT NULL DEFAULT '{}',
    arrived_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_wevt_window_type  ON window_events (window_id, record_type);
CREATE INDEX IF NOT EXISTS idx_wevt_profile_time ON window_events (profile_id, arrived_at DESC);
CREATE INDEX IF NOT EXISTS idx_wevt_client_time  ON window_events (client_id, arrived_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_wevt_dedup
    ON window_events (window_id, deduplication_key)
    WHERE deduplication_key IS NOT NULL;

-- ── file_specs ────────────────────────────────────────────────────────────────
-- File format specification registry — single source of truth for how a file
-- is parsed, validated, corrected, and generated.
--
-- How specs tie into the workflow:
--   INGEST:    PARSE_FILE  step config { "fileSpecId": "<id>" }
--              → executor loads spec, drives XML/CSV/FIXED-WIDTH parsing
--   VALIDATE:  uses spec.validationRules (REQUIRED, REGEX, RANGE, MIN_LENGTH …)
--   CORRECT:   uses spec.correctionRules (TRIM, UPPERCASE, DATE_COERCE …)
--   GENERATE:  GENERATE_FILE step config { "fileSpecId": "<id>" }
--              → executor loads spec.outputSpec, drives file generation
--
-- Versioning: name+version is globally unique. Upgrading creates a NEW row with
-- a bumped version — old spec stays unchanged so in-flight workflows don't break.
-- Profile steps always reference spec by ID (immutable pointer).

CREATE TABLE IF NOT EXISTS file_specs (
    id                  VARCHAR(255) PRIMARY KEY,
    name                VARCHAR(255) NOT NULL,
    description         TEXT         NOT NULL DEFAULT '',
    version             VARCHAR(50)  NOT NULL DEFAULT '1.0',
    format              VARCHAR(50)  NOT NULL,
    encoding            VARCHAR(50)  NOT NULL DEFAULT 'UTF-8',
    has_header          BOOLEAN      NOT NULL DEFAULT false,
    delimiter           VARCHAR(10),
    record_separator    VARCHAR(10)  NOT NULL DEFAULT '\n',
    skip_lines_count    INT          NOT NULL DEFAULT 0,
    fields              JSONB        NOT NULL DEFAULT '[]',
    validation_rules    JSONB        NOT NULL DEFAULT '[]',
    correction_rules    JSONB        NOT NULL DEFAULT '[]',
    output_spec         JSONB,
    metadata            JSONB        NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by          VARCHAR(255) NOT NULL DEFAULT 'system',
    CONSTRAINT uq_file_spec_name_version UNIQUE (name, version)
);

CREATE INDEX IF NOT EXISTS idx_fspec_format  ON file_specs (format);
CREATE INDEX IF NOT EXISTS idx_fspec_updated ON file_specs (updated_at DESC);

-- ── window_action_executions ──────────────────────────────────────────────────
-- One row per Action execution per WindowInstance (renamed from workflow_executions).
--
-- Concept: "execution of Action A on Window W".
--   A Profile has N Actions. When a Window closes, each Action whose condition
--   matches fires and produces one window_action_executions row.
--
-- Crash recovery: on app startup a job queries
--   SELECT * FROM window_action_executions WHERE status IN ('RUNNING','RETRYING')
-- and resumes each from checkpoint_data at current_step_index.

CREATE TABLE IF NOT EXISTS window_action_executions (
    execution_id            VARCHAR(255) PRIMARY KEY,
    window_id               VARCHAR(255) NOT NULL REFERENCES windows (window_id),
    profile_id              VARCHAR(255) NOT NULL REFERENCES profiles (id),
    profile_version         INT          NOT NULL,
    action_id               VARCHAR(255) NOT NULL,
    action_name             VARCHAR(255) NOT NULL,
    status                  VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    current_step_index      INT          NOT NULL DEFAULT 0,
    checkpoint_data         JSONB        NOT NULL DEFAULT '{}',
    total_records_processed INT          NOT NULL DEFAULT 0,
    total_attempts          INT          NOT NULL DEFAULT 1,
    error_message           TEXT,
    started_at              TIMESTAMPTZ,
    last_updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_wae_active
    ON window_action_executions (window_id)
    WHERE status IN ('PENDING', 'RUNNING', 'RETRYING');

CREATE INDEX IF NOT EXISTS idx_wae_status       ON window_action_executions (status);
CREATE INDEX IF NOT EXISTS idx_wae_window       ON window_action_executions (window_id);
CREATE INDEX IF NOT EXISTS idx_wae_profile      ON window_action_executions (profile_id);
CREATE INDEX IF NOT EXISTS idx_wae_last_updated ON window_action_executions (last_updated_at DESC);

-- ── workflow_step_executions ──────────────────────────────────────────────────
-- One row per step execution attempt (child of window_action_executions).
-- Multiple rows for the same (execution_id, step_id) = retries.
--
-- Idempotency: if a COMPLETED row exists with the same step_id + input_checksum,
-- the orchestrator skips re-execution and reuses output_summary.

CREATE TABLE IF NOT EXISTS workflow_step_executions (
    step_execution_id   VARCHAR(255) PRIMARY KEY,
    execution_id        VARCHAR(255) NOT NULL
                            REFERENCES window_action_executions (execution_id) ON DELETE CASCADE,
    step_id             VARCHAR(255) NOT NULL,
    step_index          INT          NOT NULL,
    step_name           VARCHAR(255) NOT NULL,
    status              VARCHAR(50)  NOT NULL DEFAULT 'PENDING',
    attempt             INT          NOT NULL DEFAULT 1,
    input_checksum      VARCHAR(64),
    output_summary      TEXT,
    records_processed   INT          NOT NULL DEFAULT 0,
    error_message       TEXT,
    error_type          VARCHAR(255),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_stepexec_execution ON workflow_step_executions (execution_id);
CREATE INDEX IF NOT EXISTS idx_stepexec_step      ON workflow_step_executions (execution_id, step_id);
CREATE INDEX IF NOT EXISTS idx_stepexec_status    ON workflow_step_executions (status);
