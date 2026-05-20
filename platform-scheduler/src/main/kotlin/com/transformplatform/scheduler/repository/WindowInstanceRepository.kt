package com.transformplatform.scheduler.repository

import com.transformplatform.common.domain.window.FileHandle
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.domain.window.WindowStatus
import java.time.Instant

// ── WindowInstanceRepository ──────────────────────────────────────────────────
//
// Contract for WindowInstance persistence.
//
// Phase 1a implementation: JPA repository backed by the `windows` table.
// For now the interface defines the contract; the in-memory stub below is used
// until Phase 1a wires the real JPA implementation.
//
// The two orchestrator queries are the hot path — they run every 30 seconds:
//
//   findDueToOpen(now):
//     SELECT * FROM windows
//     WHERE status = 'PENDING'
//       AND scheduled_open_at IS NOT NULL
//       AND scheduled_open_at <= :now
//     FOR UPDATE SKIP LOCKED         ← cluster-safe; each node claims its own batch
//     LIMIT 100                      ← configurable page size
//
//   findDueToClose(now):
//     SELECT * FROM windows
//     WHERE status = 'OPEN'
//       AND scheduled_close_at IS NOT NULL
//       AND scheduled_close_at <= :now
//     FOR UPDATE SKIP LOCKED
//     LIMIT 100
//
// Phase 1a indexes needed:
//   CREATE INDEX idx_windows_pending_open   ON windows (scheduled_open_at)  WHERE status = 'PENDING';
//   CREATE INDEX idx_windows_open_close     ON windows (scheduled_close_at) WHERE status = 'OPEN';
//   CREATE INDEX idx_windows_profile_status ON windows (profile_id, status);

interface WindowInstanceRepository {

    fun save(instance: WindowInstance): WindowInstance

    fun findById(id: String): WindowInstance?

    /** All windows for a profile, newest first. */
    fun findByProfileId(profileId: String): List<WindowInstance>

    /** The currently PENDING window for a profile, if any. */
    fun findPendingForProfile(profileId: String): WindowInstance?

    /**
     * The currently OPEN or CLOSING window for a profile.
     * Used by the overlap guard in WindowStateService.open():
     * if non-null, OverlapPolicy is consulted before opening a new window.
     */
    fun findActiveForProfile(profileId: String): WindowInstance?

    // ── Orchestrator polling queries ──────────────────────────────────────────

    /**
     * Windows that are due to open (PENDING and scheduledOpenAt <= now).
     * Called by WindowOpenChecker on every orchestrator tick.
     *
     * Phase 1a: implement with FOR UPDATE SKIP LOCKED for cluster safety.
     * Phase 1a: add partial index on scheduled_open_at WHERE status='PENDING'.
     */
    fun findDueToOpen(now: Instant = Instant.now()): List<WindowInstance>

    /**
     * Windows that are due to close (OPEN and scheduledCloseAt <= now).
     * Called by WindowCloseChecker on every orchestrator tick.
     *
     * Phase 1a: implement with FOR UPDATE SKIP LOCKED for cluster safety.
     * Phase 1a: add partial index on scheduled_close_at WHERE status='OPEN'.
     */
    fun findDueToClose(now: Instant = Instant.now()): List<WindowInstance>

    /**
     * All OPEN windows whose profile has a SESSION_GAP close trigger.
     * Called by WindowCloseChecker to evaluate session expiry.
     * Phase 1a: add a trigger_type column (denormalized from profile) for efficient filtering.
     */
    fun findOpenWithSessionGapTrigger(): List<WindowInstance>

    // ── Mutations ─────────────────────────────────────────────────────────────

    /** Transition: atomically update the status column and set timestamps. */
    fun updateStatus(id: String, newStatus: WindowStatus, reason: String? = null, timestamp: Instant = Instant.now()): WindowInstance

    /**
     * Set scheduledCloseAt after a window opens.
     * Called by WindowOpenChecker immediately after opening.
     * Phase 1a: UPDATE windows SET scheduled_close_at = ? WHERE id = ?
     */
    fun setScheduledCloseAt(id: String, scheduledCloseAt: Instant): WindowInstance

    /**
     * Atomic event count increment.
     * Returns the new count after increment.
     * SQL: UPDATE windows SET event_count = event_count + delta WHERE id = ?
     */
    fun incrementEventCount(id: String, delta: Int = 1): Int

    /** Append a FileHandle to the arrivedFileHandles JSON array. */
    fun addArrivedFile(id: String, file: FileHandle): WindowInstance

    /** Persist updated compound trigger fire state (JSONB). */
    fun updateCloseTriggerState(id: String, fireState: com.transformplatform.common.domain.trigger.TriggerFireState): WindowInstance

    fun deleteById(id: String)

    /** All windows with the given status. */
    fun findByStatus(status: WindowStatus): List<WindowInstance>

    /** All windows, newest first (admin/UI overview). */
    fun findAll(): List<WindowInstance>
}

// ── InMemoryWindowInstanceRepository ─────────────────────────────────────────
//
// Temporary in-memory implementation used until Phase 1a adds JPA.
// Thread-safe via ConcurrentHashMap.
// Replace with a JpaRepository<WindowEntity, String> adapter in Phase 1a.

class InMemoryWindowInstanceRepository : WindowInstanceRepository {

    private val store = java.util.concurrent.ConcurrentHashMap<String, WindowInstance>()

    override fun save(instance: WindowInstance): WindowInstance = instance.also { store[it.id] = it }

    override fun findById(id: String): WindowInstance? = store[id]

    override fun findByProfileId(profileId: String): List<WindowInstance> = store.values
        .filter { it.profileId == profileId }
        .sortedByDescending { it.createdAt }

    override fun findPendingForProfile(profileId: String): WindowInstance? = store.values.firstOrNull {
        it.profileId == profileId && it.status == WindowStatus.PENDING
    }

    override fun findActiveForProfile(profileId: String): WindowInstance? = store.values.firstOrNull {
        it.profileId == profileId &&
            it.status in setOf(WindowStatus.OPEN, WindowStatus.CLOSING)
    }

    override fun findDueToOpen(now: Instant): List<WindowInstance> = store.values.filter { window ->
        window.status == WindowStatus.PENDING &&
            window.scheduledOpenAt?.let { !it.isAfter(now) } == true
    }

    override fun findDueToClose(now: Instant): List<WindowInstance> = store.values.filter { window ->
        window.status == WindowStatus.OPEN &&
            window.scheduledCloseAt?.let { !it.isAfter(now) } == true
    }

    override fun findOpenWithSessionGapTrigger(): List<WindowInstance> {
        // In-memory: no way to know the trigger type without loading profiles.
        // In Phase 1a: add a denormalized trigger_type column to the windows table.
        // For now, return all OPEN windows — the caller filters by trigger type.
        return store.values.filter { it.status == WindowStatus.OPEN }
    }

    override fun updateStatus(id: String, newStatus: WindowStatus, reason: String?, timestamp: Instant): WindowInstance {
        val existing = store[id] ?: error("WindowInstance $id not found")
        val updated = when (newStatus) {
            WindowStatus.OPEN -> existing.copy(
                status = newStatus,
                openedAt = timestamp,
                updatedAt = timestamp,
            )
            WindowStatus.CLOSING -> existing.copy(
                status = newStatus,
                closingStartedAt = timestamp,
                updatedAt = timestamp,
            )
            WindowStatus.CLOSED, WindowStatus.FORCE_CLOSED, WindowStatus.ERROR -> existing.copy(
                status = newStatus,
                closedAt = timestamp,
                statusReason = reason,
                updatedAt = timestamp,
            )
            else -> existing.copy(status = newStatus, updatedAt = timestamp)
        }
        store[id] = updated
        return updated
    }

    override fun setScheduledCloseAt(id: String, scheduledCloseAt: Instant): WindowInstance {
        val existing = store[id] ?: error("WindowInstance $id not found")
        val updated = existing.copy(
            scheduledCloseAt = scheduledCloseAt,
            updatedAt = Instant.now(),
        )
        store[id] = updated
        return updated
    }

    override fun incrementEventCount(id: String, delta: Int): Int {
        val existing = store[id] ?: error("WindowInstance $id not found")
        val updated = existing.copy(
            eventCount = existing.eventCount + delta,
            updatedAt = Instant.now(),
        )
        store[id] = updated
        return updated.eventCount
    }

    override fun addArrivedFile(id: String, file: FileHandle): WindowInstance {
        val existing = store[id] ?: error("WindowInstance $id not found")
        val updated = existing.copy(
            arrivedFileHandles = existing.arrivedFileHandles + file,
            updatedAt = Instant.now(),
        )
        store[id] = updated
        return updated
    }

    override fun updateCloseTriggerState(
        id: String,
        fireState: com.transformplatform.common.domain.trigger.TriggerFireState,
    ): WindowInstance {
        val existing = store[id] ?: error("WindowInstance $id not found")
        val updated = existing.copy(
            closeTriggerState = fireState,
            updatedAt = Instant.now(),
        )
        store[id] = updated
        return updated
    }

    override fun deleteById(id: String) {
        store.remove(id)
    }

    override fun findByStatus(status: WindowStatus): List<WindowInstance> =
        store.values.filter { it.status == status }.sortedByDescending { it.createdAt }

    override fun findAll(): List<WindowInstance> = store.values.sortedByDescending { it.createdAt }
}
