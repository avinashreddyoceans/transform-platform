package com.transformplatform.api.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.ColumnTransformer
import java.time.Instant

// ── WorkflowExecutionJpaEntity ────────────────────────────────────────────────
//
// JPA representation of the `window_action_executions` table (renamed from
// workflow_executions). One row = one Action executed against one Window.
//
// PK column is `execution_id` — @Column on @Id maps Kotlin `id` → DB `execution_id`.
// FK column is `window_id` — references windows(window_id).
//   The Kotlin field is named `windowId` to match the domain model's
//   `windowInstanceId` concept (renamed for clarity — a "window instance" IS a window).
//
// `checkpoint_data` JSONB holds the output of the last completed step.
// On crash recovery, WorkflowRecoveryService resumes from `current_step_index`
// passing checkpoint_data as the input to that step.

@Entity
@Table(name = "window_action_executions")
class WorkflowExecutionJpaEntity {

    @Id
    @Column(name = "execution_id", nullable = false, length = 255)
    var id: String = ""

    /** FK to windows(window_id). The window this Action was executed against. */
    @Column(name = "window_id", nullable = false, length = 255)
    var windowId: String = ""

    @Column(name = "profile_id", nullable = false, length = 255)
    var profileId: String = ""

    @Column(name = "profile_version", nullable = false)
    var profileVersion: Int = 0

    @Column(name = "action_id", nullable = false, length = 255)
    var actionId: String = ""

    @Column(name = "action_name", nullable = false, length = 255)
    var actionName: String = ""

    /** WorkflowStatus enum name */
    @Column(name = "status", nullable = false, length = 50)
    var status: String = "PENDING"

    @Column(name = "current_step_index", nullable = false)
    var currentStepIndex: Int = 0

    /** Map<String, Any> serialised as JSON — checkpoint for crash recovery */
    @ColumnTransformer(read = "checkpoint_data::text", write = "?::jsonb")
    @Column(name = "checkpoint_data", nullable = false)
    var checkpointData: String = "{}"

    @Column(name = "total_records_processed", nullable = false)
    var totalRecordsProcessed: Int = 0

    @Column(name = "total_attempts", nullable = false)
    var totalAttempts: Int = 1

    @Column(name = "error_message")
    var errorMessage: String? = null

    @Column(name = "started_at")
    var startedAt: Instant? = null

    @Column(name = "last_updated_at", nullable = false)
    var lastUpdatedAt: Instant = Instant.now()

    @Column(name = "completed_at")
    var completedAt: Instant? = null
}
