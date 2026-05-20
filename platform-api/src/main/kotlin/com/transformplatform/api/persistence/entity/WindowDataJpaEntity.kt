package com.transformplatform.api.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import java.time.Instant

// ── WindowDataJpaEntity ───────────────────────────────────────────────────────
//
// JPA representation of the `window_events` table (renamed from window_data).
//
// The PK column is `event_id` — explicit @Column on the @Id field maps
// Kotlin field `id` → DB column `event_id`. Domain code continues to use `.id`.
//
// FK `window_id` references windows(window_id).
//
// `record_type` is an application-defined classification for the event payload
// (e.g. "CAMT053_ENTRY", "ACH_TRANSACTION"). It drives:
//   • Step routing — PARSE_FILE steps filter by record_type.
//   • EventCount trigger evaluation — the trigger can fire on count of a specific type.
//   • UI filtering — the window detail page filters by record_type.
//
// `source_integration_id` is the ServiceIntegration ID that delivered the event
// (e.g. the SFTP listener integration UUID). Used for:
//   • Audit — trace which integration produced each event.
//   • Deduplication scope — combined with deduplication_key to prevent duplicates
//     from the same integration re-delivering the same event.

@Entity
@Table(name = "window_events")
class WindowDataJpaEntity {

    @Id
    @Column(name = "event_id", nullable = false, length = 255)
    var id: String = ""

    @Column(name = "window_id", nullable = false, length = 255)
    var windowId: String = ""

    @Column(name = "profile_id", nullable = false, length = 255)
    var profileId: String = ""

    @Column(name = "client_id", nullable = false, length = 255)
    var clientId: String = ""

    /** Stores the WindowDataRecordType or free-form application type string. */
    @Column(name = "record_type", nullable = false, length = 50)
    var recordType: String = ""

    /**
     * ID of the ServiceIntegration that delivered this event.
     * Null if the event was injected programmatically (e.g. API ingestion).
     */
    @Column(name = "source_integration_id", length = 255)
    var sourceIntegrationId: String? = null

    /**
     * Optional idempotency key scoped to (window_id, deduplication_key).
     * If set, the adapter will reject a second insert with the same key
     * in the same window (throws DuplicateRecordException).
     */
    @Column(name = "deduplication_key", length = 512)
    var deduplicationKey: String? = null

    /** Business timestamp of the event, if available from the source record. */
    @Column(name = "event_timestamp")
    var eventTimestamp: Instant? = null

    /**
     * The event payload serialised as a JSON object.
     * Map<String, Any> is stored via Jackson (JSONB in Postgres).
     */
    @ColumnTransformer(read = "payload::text", write = "?::jsonb")
    @Column(name = "payload", nullable = false)
    var payload: String = "{}"

    @Column(name = "arrived_at", nullable = false)
    var arrivedAt: Instant = Instant.now()
}
