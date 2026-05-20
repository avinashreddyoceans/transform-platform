package com.transformplatform.api.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import java.time.Instant

// ── WindowJpaEntity ───────────────────────────────────────────────────────────
//
// JPA representation of the `windows` table.
//
// The PK column is `window_id` (not the conventional `id`) — explicit @Column
// annotation on the @Id field maps Kotlin field `id` → DB column `window_id`.
// This keeps the domain model and adapter code unchanged (they use `.id`)
// while matching the SQL schema FK convention (windows(window_id)).
//
// JSONB columns (arrived_file_handles, close_trigger_state) are stored as
// String JSON text and converted to/from domain types by JpaWindowRepositoryAdapter.
//
// The profile_id column references profiles(id) in SQL but is stored as a plain
// String here — no @ManyToOne mapping. The adapter loads Profile separately
// through ProfileRepository, keeping the domain boundary clean.

@Entity
@Table(name = "windows")
class WindowJpaEntity {

    @Id
    @Column(name = "window_id")
    var id: String = ""

    @Column(name = "profile_id")
    var profileId: String = ""

    @Column(name = "profile_version")
    var profileVersion: Int = 1

    // VARCHAR — matches WindowStatus enum name
    var status: String = "PENDING"

    // ── Scheduling pointers ───────────────────────────────────────────────────

    @Column(name = "scheduled_open_at")
    var scheduledOpenAt: Instant? = null

    @Column(name = "scheduled_close_at")
    var scheduledCloseAt: Instant? = null

    // ── Lifecycle timestamps ──────────────────────────────────────────────────

    @Column(name = "opened_at")
    var openedAt: Instant? = null

    @Column(name = "closing_started_at")
    var closingStartedAt: Instant? = null

    @Column(name = "closed_at")
    var closedAt: Instant? = null

    @Column(name = "status_reason")
    var statusReason: String? = null

    // ── Collected data summary ────────────────────────────────────────────────

    @Column(name = "event_count")
    var eventCount: Int = 0

    // JSONB: List<String> — each entry is an opaque file-handle reference
    // (e.g. SFTP path, S3 key, integration-specific ID) for files that have
    // arrived in this window. Populated by the file-arrival trigger handler.
    @ColumnTransformer(read = "arrived_file_handles::text", write = "?::jsonb")
    @Column(name = "arrived_file_handles")
    var arrivedFileHandles: String = "[]"

    // ── Compound trigger checkpoint ───────────────────────────────────────────
    // Tracks which sub-triggers of a COMPOUND close trigger have fired.
    // Schema: { "fired": { "triggerKey": true }, "firedAt": { "triggerKey": "<ISO>" } }
    @ColumnTransformer(read = "close_trigger_state::text", write = "?::jsonb")
    @Column(name = "close_trigger_state")
    var closeTriggerState: String = """{"fired":{},"firedAt":{}}"""

    // ── Audit fields ──────────────────────────────────────────────────────────

    @Column(name = "created_at")
    var createdAt: Instant = Instant.now()

    @Column(name = "updated_at")
    var updatedAt: Instant = Instant.now()
}
