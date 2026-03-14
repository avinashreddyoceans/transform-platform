package com.transformplatform.integration.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

// ── Enum ──────────────────────────────────────────────────────────────────────

enum class FileProcessingStatus {
    /** File has been downloaded to MinIO and is waiting for the pipeline. */
    PENDING,

    /** A pipeline worker has claimed this file and is processing it. */
    PROCESSING,

    /** File has been successfully transformed and published. */
    PROCESSED,

    /** Processing failed after all retries. */
    FAILED,
}

// ── Entity ────────────────────────────────────────────────────────────────────

/**
 * Tracks every file that has been downloaded from a remote connector and stored
 * in the MinIO backup store.
 *
 * This table serves two purposes:
 *
 * 1. **Idempotency ledger** – the unique constraint on
 *    `(integration_id, remote_file_name)` ensures the same remote file is never
 *    downloaded twice, even if the Camel route is restarted or the app is
 *    redeployed.
 *
 * 2. **"Ready for pickup" queue** – the action-scheduling pipeline polls for
 *    rows where [processingStatus] = [FileProcessingStatus.PENDING], claims them
 *    (sets status to PROCESSING), fetches the file from MinIO via [storageKey],
 *    and processes it through the transform pipeline.
 */
@Entity
@Table(
    name = "downloaded_files",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uq_download_per_integration",
            columnNames = ["integration_id", "remote_file_name"],
        ),
    ],
)
class DownloadedFile(

    @Id
    val id: String = UUID.randomUUID().toString(),

    // ── Source identity ────────────────────────────────────────────────────

    @Column(name = "integration_id", nullable = false)
    val integrationId: String,

    @Column(name = "remote_file_name", nullable = false)
    val remoteFileName: String,

    @Column(name = "remote_file_path")
    val remoteFilePath: String? = null,

    @Column(name = "file_size_bytes")
    val fileSizeBytes: Long? = null,

    @Column(name = "remote_last_modified")
    val remoteLastModified: Instant? = null,

    /** MD5 hex digest of the downloaded content for integrity verification. */
    @Column(name = "md5_checksum")
    val md5Checksum: String? = null,

    // ── Storage location (MinIO) ───────────────────────────────────────────

    /** MinIO bucket where the file is stored — typically `transform-downloads`. */
    @Column(name = "storage_bucket", nullable = false)
    val storageBucket: String,

    /**
     * MinIO object key — typically `{integrationId}/{date}/{fileName}`.
     * Use this key to retrieve the file from MinIO for processing.
     */
    @Column(name = "storage_key", nullable = false)
    val storageKey: String,

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    var processingStatus: FileProcessingStatus = FileProcessingStatus.PENDING,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    @Column(name = "downloaded_at", nullable = false, updatable = false)
    val downloadedAt: Instant = Instant.now(),

    @Column(name = "processing_started_at")
    var processingStartedAt: Instant? = null,

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Version
    @Column(nullable = false)
    var version: Int = 1,
)
