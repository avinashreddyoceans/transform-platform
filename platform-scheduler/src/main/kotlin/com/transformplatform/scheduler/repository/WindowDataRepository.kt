package com.transformplatform.scheduler.repository

import com.transformplatform.common.domain.window.WindowDataRecord
import com.transformplatform.common.domain.window.WindowDataRecordType
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// ── WindowDataRepository ──────────────────────────────────────────────────────
//
// The data collection interface.
//
// This is how data flows into the system and gets associated with a window.
// Every piece of ingested data — events, files, parsed records — ends up here,
// keyed by windowId so the action chain can retrieve it at close time.
//
// Access patterns (all indexed in Phase 1a):
//
//  1. Ingest (write path):
//       INSERT INTO window_data (window_id, profile_id, ...) VALUES (...)
//       Called by:
//         • EventIngestionService   — for incoming events/messages
//         • FileArrivalService      — when a file is downloaded from SFTP/S3
//         • StepExecutor            — after PARSE_FILE, TRANSFORM_RECORD, etc.
//
//  2. At window close (read path — hot query):
//       SELECT * FROM window_data
//       WHERE window_id = :windowId
//         AND record_type = :type
//       ORDER BY arrived_at
//       This is what the action chain's steps operate on.
//
//  3. Deduplication check (write path guard):
//       SELECT 1 FROM window_data
//       WHERE window_id = :windowId AND deduplication_key = :key
//       LIMIT 1
//       Or: enforced by UNIQUE(window_id, deduplication_key) index + catch constraint violation.
//
//  4. Count queries (for EventCount trigger threshold evaluation):
//       SELECT COUNT(*) FROM window_data
//       WHERE window_id = :windowId AND record_type = 'RAW_EVENT'
//       NOTE: WindowInstance.eventCount is a cached counter for this — avoid full COUNT(*) in prod.
//
// Phase 1a DB schema:
//   CREATE TABLE window_data (
//     id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
//     window_id           UUID NOT NULL REFERENCES windows(id),
//     profile_id          UUID NOT NULL,
//     client_id           VARCHAR NOT NULL,
//     record_type         VARCHAR NOT NULL,
//     source_integration  VARCHAR,
//     deduplication_key   VARCHAR,
//     event_timestamp     TIMESTAMPTZ,
//     payload             JSONB NOT NULL DEFAULT '{}',
//     arrived_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
//   );
//
//   CREATE INDEX idx_wdata_window_type    ON window_data (window_id, record_type);
//   CREATE INDEX idx_wdata_profile_time   ON window_data (profile_id, arrived_at DESC);
//   CREATE UNIQUE INDEX idx_wdata_dedup   ON window_data (window_id, deduplication_key)
//     WHERE deduplication_key IS NOT NULL;

interface WindowDataRepository {

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Persist a single data record into the window's bucket.
     *
     * If deduplication is active and a record with the same (windowId, deduplicationKey)
     * already exists, throws [DuplicateRecordException].
     *
     * Phase 1a: the UNIQUE index enforces this at the DB level.
     */
    fun save(record: WindowDataRecord): WindowDataRecord

    /**
     * Persist multiple records in one operation (batch ingest).
     * Returns only the records actually saved (duplicates filtered out if skipDuplicates=true).
     */
    fun saveAll(records: List<WindowDataRecord>, skipDuplicates: Boolean = false): List<WindowDataRecord>

    // ── Read ──────────────────────────────────────────────────────────────────

    fun findById(id: String): WindowDataRecord?

    /**
     * All records for a window, optionally filtered by type.
     * This is the primary read at window-close time — the action chain calls this
     * to get all data collected during the window.
     *
     * Phase 1a: SELECT * FROM window_data WHERE window_id = ? [AND record_type = ?]
     * Returns records ordered by arrivedAt ascending (natural processing order).
     */
    fun findByWindow(windowId: String, recordType: WindowDataRecordType? = null): List<WindowDataRecord>

    /**
     * Count records for a window by type.
     * Used by EventCount trigger threshold evaluation.
     * Prefer WindowInstance.eventCount (cached) for hot-path threshold checks —
     * use this for accuracy checks or when eventCount is unavailable.
     */
    fun countByWindow(windowId: String, recordType: WindowDataRecordType? = null): Int

    /**
     * Check whether a deduplication key has already been seen in this window.
     * Returns true if the record is a duplicate.
     */
    fun isDuplicate(windowId: String, deduplicationKey: String): Boolean

    /**
     * Records for a profile across all windows, newest first.
     * Used for audit/admin views and late-event correlation.
     */
    fun findByProfile(
        profileId: String,
        recordType: WindowDataRecordType? = null,
        since: Instant? = null,
        limit: Int = 100,
    ): List<WindowDataRecord>

    /**
     * Delete all data records for a window.
     * Called when a window is purged (OverlapPolicy.FORCE_CLOSE_EXISTING on a stale window,
     * or an admin purge operation).
     */
    fun deleteByWindow(windowId: String): Int
}

// ── DuplicateRecordException ──────────────────────────────────────────────────

class DuplicateRecordException(
    windowId: String,
    deduplicationKey: String,
) : RuntimeException(
    "Duplicate record rejected: windowId=$windowId, deduplicationKey=$deduplicationKey",
)

// ── InMemoryWindowDataRepository ─────────────────────────────────────────────
//
// In-memory stub for Phase 0.  Replace with JPA in Phase 1a.

class InMemoryWindowDataRepository : WindowDataRepository {

    private val store = ConcurrentHashMap<String, WindowDataRecord>()

    override fun save(record: WindowDataRecord): WindowDataRecord {
        // Capture to local val so Kotlin can smart-cast across module boundary
        val dedupKey = record.deduplicationKey
        if (dedupKey != null && isDuplicate(record.windowId, dedupKey)) {
            throw DuplicateRecordException(record.windowId, dedupKey)
        }
        store[record.id] = record
        return record
    }

    override fun saveAll(records: List<WindowDataRecord>, skipDuplicates: Boolean): List<WindowDataRecord> {
        val saved = mutableListOf<WindowDataRecord>()
        for (record in records) {
            try {
                saved.add(save(record))
            } catch (ex: DuplicateRecordException) {
                if (!skipDuplicates) throw ex
                // else: silently skip
            }
        }
        return saved
    }

    override fun findById(id: String): WindowDataRecord? = store[id]

    override fun findByWindow(windowId: String, recordType: WindowDataRecordType?): List<WindowDataRecord> = store.values
        .filter { it.windowId == windowId }
        .filter { recordType == null || it.recordType == recordType }
        .sortedBy { it.arrivedAt }

    override fun countByWindow(windowId: String, recordType: WindowDataRecordType?): Int = store.values.count {
        it.windowId == windowId && (recordType == null || it.recordType == recordType)
    }

    override fun isDuplicate(windowId: String, deduplicationKey: String): Boolean = store.values.any {
        it.windowId == windowId && it.deduplicationKey == deduplicationKey
    }

    override fun findByProfile(profileId: String, recordType: WindowDataRecordType?, since: Instant?, limit: Int): List<WindowDataRecord> =
        store.values
            .filter { it.profileId == profileId }
            .filter { recordType == null || it.recordType == recordType }
            .filter { since == null || !it.arrivedAt.isBefore(since) }
            .sortedByDescending { it.arrivedAt }
            .take(limit)

    override fun deleteByWindow(windowId: String): Int {
        val keys = store.keys.filter { store[it]?.windowId == windowId }
        keys.forEach { store.remove(it) }
        return keys.size
    }
}
