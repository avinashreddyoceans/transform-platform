package com.transformplatform.common.domain.window

import java.time.Instant
import java.util.UUID

// ── WindowDataRecord ──────────────────────────────────────────────────────────
//
// A single unit of data collected into a window.
//
// The window is the bucket.  Every piece of data that arrives while a window is
// OPEN is stamped with the window's ID so it can be:
//   • Queried by window:    SELECT * FROM window_data WHERE window_id = ?
//   • Aggregated at close:  COUNT(*), SUM(amount), GROUP BY source_id, etc.
//   • Replayed:             reprocess all records for a window without re-ingesting
//   • Traced back:          windowId → WindowInstance → profileId → Profile (config)
//
// There is one WindowDataRecord table.  The recordType discriminates what kind of
// data the record contains:
//   RAW_EVENT       — an inbound event received via HTTP or Kafka before parsing
//   PARSED_RECORD   — a fully parsed and validated domain record (post-PARSE_FILE step)
//   FILE_ARRIVED    — a file arrived on a watched integration (mirrors FileHandle but
//                     also in the data table for unified querying)
//   TRANSFORMED     — a record after TRANSFORM_RECORD step
//   DEDUPLICATED    — a record that survived deduplication (DEDUPLICATE step)
//   NOTIFICATION    — a record mapped by MAP_TO_NOTIFICATION step
//
// DB table: window_data
//   id                  UUID PK
//   window_id           UUID FK → windows(id)          ← the bucket key
//   profile_id          UUID FK → profiles(id)          ← denormalized for fast per-client queries
//   client_id           VARCHAR                         ← denormalized (avoid join to profiles)
//   record_type         VARCHAR
//   source_integration  VARCHAR NULL
//   deduplication_key   VARCHAR NULL                    ← unique per window if dedup is on
//   event_timestamp     TIMESTAMPTZ NULL                ← for EVENT_TIME semantics
//   payload             JSONB                           ← the actual data
//   arrived_at          TIMESTAMPTZ
//
// Indexes:
//   (window_id, record_type)      ← primary access pattern at window close
//   (window_id, deduplication_key) UNIQUE  ← enforces deduplication per window
//   (profile_id, arrived_at)      ← per-profile recent data queries
//   (client_id, arrived_at)       ← per-client admin queries

data class WindowDataRecord(

    val id: String = UUID.randomUUID().toString(),

    /**
     * The window this record belongs to.
     * This is the primary foreign key: every record is owned by exactly one window.
     * At window close time, the action chain queries ALL records for this windowId.
     */
    val windowId: String,

    /**
     * Denormalized from WindowInstance for efficient per-profile queries
     * without joining to the windows table.
     */
    val profileId: String,

    /**
     * Denormalized for per-client queries and multi-tenant isolation.
     */
    val clientId: String,

    /** What kind of data this record represents — the discriminator. */
    val recordType: WindowDataRecordType,

    /**
     * Which integration this record came from.
     * e.g. "SFTP_BANK_A", "HTTP_WEBHOOK_PARTNER", "KAFKA_ORDERS"
     * Null for internally generated records (e.g. DEDUPLICATED, NOTIFICATION).
     */
    val sourceIntegrationId: String? = null,

    /**
     * The deduplication key for this record within its window.
     * When deduplication is enabled on the Profile, the engine rejects records
     * with a key already present in this window.
     *
     * Enforced by a UNIQUE index on (window_id, deduplication_key).
     * Null = deduplication not applicable to this record type.
     */
    val deduplicationKey: String? = null,

    /**
     * The event timestamp embedded in the record.
     * Used when WindowConfig.timeSemantics = EVENT_TIME.
     * For PROCESSING_TIME semantics, use arrivedAt instead.
     * Null if the record has no embedded timestamp or doesn't support EVENT_TIME.
     */
    val eventTimestamp: Instant? = null,

    /**
     * The actual data payload.
     * Stored as JSONB in the database.
     *
     * The schema of this map depends on recordType:
     *   RAW_EVENT     → raw event fields as received
     *   PARSED_RECORD → domain model fields after parsing
     *   FILE_ARRIVED  → file metadata (path, size, checksum, etc.)
     *   TRANSFORMED   → record after transformation
     *   NOTIFICATION  → notification fields
     */
    val payload: Map<String, Any> = emptyMap(),

    /** When this record was collected (processing time). */
    val arrivedAt: Instant = Instant.now(),

)

// ── WindowDataRecordType ──────────────────────────────────────────────────────

enum class WindowDataRecordType {
    /** Raw inbound event — not yet parsed or validated. */
    RAW_EVENT,

    /** Fully parsed domain record — output of PARSE_FILE or event deserialization. */
    PARSED_RECORD,

    /** A file that arrived on a watched integration during this window. */
    FILE_ARRIVED,

    /** A record after TRANSFORM_RECORD step. */
    TRANSFORMED,

    /** A record that survived the DEDUPLICATE step. */
    DEDUPLICATED,

    /** A record mapped by MAP_TO_NOTIFICATION. */
    NOTIFICATION,

    /** A record output by GENERATE_FILE — reference to a generated file. */
    GENERATED_FILE_REF,
}
