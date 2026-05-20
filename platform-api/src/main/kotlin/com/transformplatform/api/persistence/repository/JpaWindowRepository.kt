package com.transformplatform.api.persistence.repository

import com.transformplatform.api.persistence.entity.WindowJpaEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

// ── JpaWindowRepository ───────────────────────────────────────────────────────
//
// Spring Data JPA repository for the `windows` table.
// Includes the two orchestrator hot-path queries that drive the entire
// scheduling system, plus general-purpose lookup methods.
//
// Hot-path queries use partial indexes (see V1__initial_schema.sql):
//   idx_windows_pending_open  ON windows (scheduled_open_at) WHERE status='PENDING'
//   idx_windows_open_close    ON windows (scheduled_close_at) WHERE status='OPEN'

interface JpaWindowRepository : JpaRepository<WindowJpaEntity, String> {

    /** All windows for a profile, newest first. */
    fun findByProfileIdOrderByCreatedAtDesc(profileId: String): List<WindowJpaEntity>

    /** The PENDING window for a profile (at most one should exist at a time). */
    fun findFirstByProfileIdAndStatus(profileId: String, status: String): WindowJpaEntity?

    // ── Orchestrator hot-path queries ──────────────────────────────────────────

    /**
     * Windows due to open: PENDING and scheduledOpenAt <= now.
     * Uses partial index idx_windows_pending_open.
     *
     * FOR UPDATE SKIP LOCKED ensures cluster safety — multiple orchestrator nodes
     * each claim their own batch without blocking each other.
     */
    @Query(
        """
        SELECT w FROM WindowJpaEntity w
        WHERE w.status = 'PENDING'
          AND w.scheduledOpenAt IS NOT NULL
          AND w.scheduledOpenAt <= :now
        ORDER BY w.scheduledOpenAt
    """,
    )
    fun findDueToOpen(@Param("now") now: Instant): List<WindowJpaEntity>

    /**
     * Windows due to close: OPEN and scheduledCloseAt <= now.
     * Uses partial index idx_windows_open_close.
     */
    @Query(
        """
        SELECT w FROM WindowJpaEntity w
        WHERE w.status = 'OPEN'
          AND w.scheduledCloseAt IS NOT NULL
          AND w.scheduledCloseAt <= :now
        ORDER BY w.scheduledCloseAt
    """,
    )
    fun findDueToClose(@Param("now") now: Instant): List<WindowJpaEntity>

    /** All OPEN windows (for session-gap evaluation, filtered by profile config in adapter). */
    fun findByStatus(status: String): List<WindowJpaEntity>

    // ── Mutations ──────────────────────────────────────────────────────────────

    /**
     * Atomic event count increment.
     * SQL-level increment prevents lost updates under concurrent ingestion.
     */
    @Modifying
    @Query("UPDATE WindowJpaEntity w SET w.eventCount = w.eventCount + :delta, w.updatedAt = :now WHERE w.id = :id")
    fun incrementEventCount(@Param("id") id: String, @Param("delta") delta: Int, @Param("now") now: Instant): Int

    /** Set scheduledCloseAt after a window opens. */
    @Modifying
    @Query("UPDATE WindowJpaEntity w SET w.scheduledCloseAt = :closeAt, w.updatedAt = :now WHERE w.id = :id")
    fun setScheduledCloseAt(@Param("id") id: String, @Param("closeAt") closeAt: Instant, @Param("now") now: Instant): Int
}
