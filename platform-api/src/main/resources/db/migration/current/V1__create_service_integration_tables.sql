-- ============================================================================
-- V1: Service Integration Schema
--
-- service_integrations  – connector registrations (SFTP / FTP / S3)
-- downloaded_files      – idempotency ledger + pipeline pickup queue
-- ============================================================================

-- ── service_integrations ─────────────────────────────────────────────────────
-- One row per onboarded integration.  The `encrypted_details` column holds an
-- AES-256 encrypted JSON blob whose shape depends on `type`:
--   SFTP → SftpDetails  (host, port, directories, userName, password, direction, filters)
--   FTP  → FtpDetails   (same as SFTP)
--   S3   → S3Details    (bucketName, region, accessKeyId, secretAccessKey, prefix, …)
--
-- Note: id uses VARCHAR(255) to align with Kotlin entity String IDs (UUID.randomUUID().toString())

CREATE TABLE service_integrations (
    id                  VARCHAR(255)  PRIMARY KEY,
    type                VARCHAR(50)  NOT NULL,          -- SFTP | FTP | S3
    user_id             VARCHAR(255) NOT NULL,
    short_description   TEXT         NOT NULL,
    is_enabled          BOOLEAN      NOT NULL DEFAULT false,
    -- AES-256 encrypted JSON — never exposed through the API
    encrypted_details   TEXT         NOT NULL,
    updated_by          VARCHAR(255) NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             INT          NOT NULL DEFAULT 1
);

CREATE INDEX idx_si_type       ON service_integrations (type);
CREATE INDEX idx_si_user_id    ON service_integrations (user_id);
CREATE INDEX idx_si_is_enabled ON service_integrations (is_enabled);

-- ── downloaded_files ─────────────────────────────────────────────────────────
-- Dual-purpose table:
--   1. Idempotency ledger — unique (integration_id, remote_file_name) prevents
--      re-downloading the same remote file even after restarts.
--   2. Pipeline pickup queue — the transform action polls rows where
--      processing_status = 'PENDING', claims them (→ PROCESSING), then fetches
--      the file from the S3 archival store (MinIO) via storage_key.

CREATE TABLE downloaded_files (
    id                    VARCHAR(255)   PRIMARY KEY,
    integration_id        VARCHAR(255)   NOT NULL
                              REFERENCES service_integrations (id) ON DELETE CASCADE,
    -- Remote file identity
    remote_file_name      VARCHAR(512)  NOT NULL,
    remote_file_path      VARCHAR(1024),
    file_size_bytes       BIGINT,
    remote_last_modified  TIMESTAMPTZ,
    md5_checksum          VARCHAR(64),
    -- Where the file lives in the S3 archival store (MinIO bucket)
    storage_bucket        VARCHAR(255)  NOT NULL,
    storage_key           VARCHAR(1024) NOT NULL,
    -- Pipeline lifecycle
    processing_status     VARCHAR(50)   NOT NULL DEFAULT 'PENDING',
    error_message         TEXT,
    downloaded_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    processing_started_at TIMESTAMPTZ,
    processed_at          TIMESTAMPTZ,
    version               INT           NOT NULL DEFAULT 1,
    CONSTRAINT uq_download_per_integration
        UNIQUE (integration_id, remote_file_name)
);

CREATE INDEX idx_df_status       ON downloaded_files (processing_status);
CREATE INDEX idx_df_integration  ON downloaded_files (integration_id);
CREATE INDEX idx_df_downloaded   ON downloaded_files (downloaded_at DESC);
