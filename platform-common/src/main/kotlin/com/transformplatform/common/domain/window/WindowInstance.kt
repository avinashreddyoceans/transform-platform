package com.transformplatform.common.domain.window

import com.transformplatform.common.domain.trigger.TriggerFireState
import java.time.Instant
import java.util.UUID

// ── WindowStatus ──────────────────────────────────────────────────────────────

enum class WindowStatus {
    /** Created but open trigger not yet fully evaluated (e.g. EVENT_TIME warmup). */
    PENDING,

    /** Window is active — accepting events, running RECURRING actions. */
    OPEN,

    /**
     * Close trigger fired — running ON_CLOSING action chain.
     * No new events accepted in this status.
     */
    CLOSING,

    /** All ON_CLOSING actions completed successfully. Terminal state. */
    CLOSED,

    /**
     * A step or action failed without recovery and the window cannot proceed.
     * The ON_ERROR chain has been (or is being) executed.
     */
    ERROR,

    /**
     * Closed by operator via POST /api/windows/{id}/close
     * or by the maxOpenDuration safety backstop.
     */
    FORCE_CLOSED,
}

// ── FileHandle ────────────────────────────────────────────────────────────────
//
// Reference to a file that arrived during this window's open period.
// Stored in WindowInstance so steps can resolve the file at execution time.

data class FileHandle(
    val integrationId: String,
    val fileName: String,
    val remotePath: String,
    val fileSizeBytes: Long? = null,
    val arrivedAt: Instant = Instant.now(),
    /** Checksum of the file content for integrity verification. */
    val contentChecksum: String? = null,
)

// ── WindowInstance ────────────────────────────────────────────────────────────
//
// The runtime state of ONE scheduled window.  One row per execution cycle.
//
// A WindowInstance is the central bucket for everything that happens during a
// profile's open period:
//   • Scheduling pointers  (scheduledOpenAt, scheduledCloseAt)
//   • Lifecycle timestamps (openedAt, closingStartedAt, closedAt)
//   • Collected data       (eventCount, arrivedFileHandles)
//   • Trigger state        (closeTriggerState — compound trigger checkpoint)
//
// Why scheduling pointers live here (not on Profile):
//   Profile is config — it is created once and versioned only when the user
//   changes the configuration.  Runtime scheduling state is not config; it
//   belongs on the entity that represents a scheduled execution: WindowInstance.
//
//   The orchestrator polls:
//     SELECT * FROM windows WHERE status=PENDING AND scheduled_open_at <= NOW()
//     SELECT * FROM windows WHERE status=OPEN    AND scheduled_close_at <= NOW()
//   both indexed, both cluster-safe with FOR UPDATE SKIP LOCKED.
//
// Relationship to the rest of the system:
//   WindowInstance.id  is the foreign key in:
//     • WindowDataRecord   — every event/file collected during the window
//     • WorkflowExecution  — every action chain run against this window
//   This means every piece of data and every execution can be traced back to
//   one profile, one config version, one time slice.

data class WindowInstance(

    val id: String = UUID.randomUUID().toString(),

    /** The owning profile. */
    val profileId: String,

    /** The profile version active when this window was created. Pinned for replay. */
    val profileVersion: Int,

    val status: WindowStatus = WindowStatus.PENDING,

    // ── Scheduling pointers ───────────────────────────────────────────────────
    //
    // These are the columns the orchestrator polls. They live on WindowInstance,
    // NOT on Profile.
    //
    // scheduledOpenAt:  set at PENDING window creation time.
    //   = next cron firing from the activation moment.
    //   Indexed: WHERE status=PENDING AND scheduled_open_at <= NOW()
    //   Null for MANUAL triggers (window opens on explicit request only).
    //
    // scheduledCloseAt: set when the window opens (PENDING → OPEN transition).
    //   = openedAt + windowDuration, or next closeCron firing after openedAt.
    //   Indexed: WHERE status=OPEN AND scheduled_close_at <= NOW()
    //   Null when the close trigger is event-driven (FILE_ARRIVAL, EVENT_COUNT,
    //   SESSION_GAP, COMPOUND) — those are evaluated per-event or per-tick.

    /**
     * When this window is scheduled to open.
     * Computed from the profile's openCron at PENDING window creation time.
     * Null for MANUAL and event-driven open triggers.
     */
    val scheduledOpenAt: Instant? = null,

    /**
     * When this window is scheduled to close.
     * Set by the engine immediately after the window opens.
     * Null for event-driven close triggers (evaluated by event handlers / session-gap checker).
     */
    val scheduledCloseAt: Instant? = null,

    // ── Lifecycle timestamps ──────────────────────────────────────────────────

    /** When the window actually opened (PENDING → OPEN). May differ from scheduledOpenAt. */
    val openedAt: Instant? = null,

    /** When the close trigger fired (OPEN → CLOSING). */
    val closingStartedAt: Instant? = null,

    /** When the window reached CLOSED or FORCE_CLOSED. */
    val closedAt: Instant? = null,

    /** Reason for FORCE_CLOSED or ERROR status. */
    val statusReason: String? = null,

    // ── Collected data summary ────────────────────────────────────────────────
    //
    // These are fast counters. The actual data records live in WindowDataRecord
    // (separate table, FK to this window). Keeping counters here avoids
    // COUNT(*) queries on the data table for threshold / progress evaluation.

    /** Running total of events/records collected. Updated atomically on each ingestion. */
    val eventCount: Int = 0,

    /**
     * Files that arrived during this window.
     * Populated by FileArrivalService; referenced by PARSE_FILE and DELIVER_FILE steps.
     * The full file content is accessed via the integration layer; only the handle is stored here.
     */
    val arrivedFileHandles: List<FileHandle> = emptyList(),

    // ── Compound trigger checkpoint ───────────────────────────────────────────
    //
    // Persisted as JSONB — survives restarts (Flink analogy: savepoint).
    // For simple triggers this is always empty.

    /**
     * Tracks which sub-triggers of a COMPOUND close trigger have fired.
     * Written on every sub-trigger fire and loaded on recovery.
     */
    val closeTriggerState: TriggerFireState = TriggerFireState(),

    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),

) {
    val isOpen: Boolean get() = status == WindowStatus.OPEN
    val isTerminal: Boolean get() = status in setOf(
        WindowStatus.CLOSED,
        WindowStatus.ERROR,
        WindowStatus.FORCE_CLOSED,
    )

    /** Find the first arrived file matching a glob pattern. */
    fun findFile(pattern: String): FileHandle? = arrivedFileHandles.firstOrNull { matchesGlob(it.fileName, pattern) }

    /** Find all arrived files matching a glob pattern. */
    fun findFiles(pattern: String): List<FileHandle> = arrivedFileHandles.filter { matchesGlob(it.fileName, pattern) }

    private fun matchesGlob(name: String, pattern: String): Boolean {
        // Convert glob to regex: * → .*, ? → .
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.matches(name)
    }
}
