package com.transformplatform.integration.repository

import com.transformplatform.integration.model.DownloadedFile
import com.transformplatform.integration.model.FileProcessingStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface DownloadedFileRepository : JpaRepository<DownloadedFile, String> {

    // ── Idempotency checks ────────────────────────────────────────────────────

    /**
     * Returns true if the given file was already downloaded for this integration.
     * This is the primary idempotency guard called by [DownloadedFileProcessor].
     */
    fun existsByIntegrationIdAndRemoteFileName(integrationId: String, remoteFileName: String): Boolean

    // ── Pipeline pickup queries ───────────────────────────────────────────────

    /**
     * Returns the next batch of PENDING files, ordered by download time.
     * Called by the action scheduler to claim files for processing.
     */
    fun findAllByProcessingStatusOrderByDownloadedAtAsc(status: FileProcessingStatus, pageable: Pageable): List<DownloadedFile>

    /** Overload for direct call without pagination. */
    fun findAllByProcessingStatus(status: FileProcessingStatus): List<DownloadedFile>

    // ── Status updates ────────────────────────────────────────────────────────

    @Modifying
    @Query(
        """
        UPDATE DownloadedFile d
        SET d.processingStatus = :status,
            d.processingStartedAt = :startedAt
        WHERE d.id = :id
    """,
    )
    fun markProcessing(id: String, status: FileProcessingStatus, startedAt: Instant): Int

    @Modifying
    @Query(
        """
        UPDATE DownloadedFile d
        SET d.processingStatus = :status,
            d.processedAt = :processedAt
        WHERE d.id = :id
    """,
    )
    fun markProcessed(id: String, status: FileProcessingStatus, processedAt: Instant): Int

    // ── Integration-scoped queries ────────────────────────────────────────────

    fun findAllByIntegrationId(integrationId: String): List<DownloadedFile>

    fun countByIntegrationIdAndProcessingStatus(integrationId: String, status: FileProcessingStatus): Long

    /** Global count by status — used by StartupValidator. */
    fun countByProcessingStatus(status: FileProcessingStatus): Long
}
