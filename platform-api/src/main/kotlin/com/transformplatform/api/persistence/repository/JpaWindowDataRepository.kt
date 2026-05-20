package com.transformplatform.api.persistence.repository

import com.transformplatform.api.persistence.entity.WindowDataJpaEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface JpaWindowDataRepository : JpaRepository<WindowDataJpaEntity, String> {

    // ── Primary access pattern: all records in a window ────────────────────────

    fun findByWindowIdOrderByArrivedAtAsc(windowId: String): List<WindowDataJpaEntity>

    fun findByWindowIdAndRecordTypeOrderByArrivedAtAsc(windowId: String, recordType: String): List<WindowDataJpaEntity>

    // ── Count queries (EventCount trigger evaluation) ──────────────────────────

    fun countByWindowId(windowId: String): Int

    fun countByWindowIdAndRecordType(windowId: String, recordType: String): Int

    // ── Deduplication check ────────────────────────────────────────────────────

    fun existsByWindowIdAndDeduplicationKey(windowId: String, deduplicationKey: String): Boolean

    // ── Per-profile queries (audit / admin) ────────────────────────────────────

    @Query(
        """
        SELECT w FROM WindowDataJpaEntity w
        WHERE w.profileId = :profileId
          AND (:recordType IS NULL OR w.recordType = :recordType)
          AND (:since IS NULL OR w.arrivedAt >= :since)
        ORDER BY w.arrivedAt DESC
    """,
    )
    fun findByProfilePaged(
        @Param("profileId") profileId: String,
        @Param("recordType") recordType: String?,
        @Param("since") since: Instant?,
        pageable: Pageable,
    ): List<WindowDataJpaEntity>

    // ── Bulk delete (window purge) ─────────────────────────────────────────────

    @Modifying
    @Query("DELETE FROM WindowDataJpaEntity w WHERE w.windowId = :windowId")
    fun deleteAllByWindowId(windowId: String): Int
}
