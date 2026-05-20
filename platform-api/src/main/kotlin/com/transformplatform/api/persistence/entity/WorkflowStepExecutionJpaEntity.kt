package com.transformplatform.api.persistence.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

// ── WorkflowStepExecutionJpaEntity ────────────────────────────────────────────
//
// JPA representation of the `workflow_step_executions` table.
// One row per step execution attempt. Multiple rows with the same stepId = retries.
//
// PK column is `step_execution_id` — @Column on @Id maps Kotlin `id` → DB col.
// FK column is `execution_id` — references window_action_executions(execution_id).
//   Kotlin field is named `executionId` to match the renamed column.
//
// Idempotency key: (execution_id, step_id, input_checksum).
// If a COMPLETED row already exists with the same key, the orchestrator skips
// re-execution and reuses the stored outputSummary.

@Entity
@Table(name = "workflow_step_executions")
class WorkflowStepExecutionJpaEntity {

    @Id
    @Column(name = "step_execution_id", nullable = false, length = 255)
    var id: String = ""

    /** FK to window_action_executions(execution_id). */
    @Column(name = "execution_id", nullable = false, length = 255)
    var executionId: String = ""

    @Column(name = "step_id", nullable = false, length = 255)
    var stepId: String = ""

    @Column(name = "step_index", nullable = false)
    var stepIndex: Int = 0

    @Column(name = "step_name", nullable = false, length = 255)
    var stepName: String = ""

    /** StepStatus enum name */
    @Column(name = "status", nullable = false, length = 50)
    var status: String = "PENDING"

    @Column(name = "attempt", nullable = false)
    var attempt: Int = 1

    @Column(name = "input_checksum", length = 64)
    var inputChecksum: String? = null

    @Column(name = "output_summary")
    var outputSummary: String? = null

    @Column(name = "records_processed", nullable = false)
    var recordsProcessed: Int = 0

    @Column(name = "error_message")
    var errorMessage: String? = null

    @Column(name = "error_type", length = 255)
    var errorType: String? = null

    @Column(name = "started_at")
    var startedAt: Instant? = null

    @Column(name = "completed_at")
    var completedAt: Instant? = null
}
