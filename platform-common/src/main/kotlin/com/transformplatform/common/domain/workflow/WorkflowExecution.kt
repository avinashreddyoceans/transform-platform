package com.transformplatform.common.domain.workflow

import java.time.Instant
import java.util.UUID

// ── WorkflowStatus ────────────────────────────────────────────────────────────

enum class WorkflowStatus {
    /** Created, not yet started by the orchestrator. */
    PENDING,

    /** Orchestrator is actively running steps. */
    RUNNING,

    /**
     * A step failed and is scheduled for retry.
     * The Quartz SimpleTrigger will re-invoke the orchestrator at
     * the computed backoff delay.
     */
    RETRYING,

    /** All steps completed without error. Terminal. */
    COMPLETED,

    /**
     * Steps completed but some had non-fatal errors (continueOnFailure = true).
     * Terminal — results may be partial.
     */
    COMPLETED_WITH_ERRORS,

    /**
     * A step failed and retries are exhausted, or a non-retryable error occurred.
     * The ON_ERROR action chain has been triggered.
     * Terminal.
     */
    FAILED,

    /** Cancelled by operator via POST /api/windows/{id}/executions/{id}/cancel. */
    CANCELLED,
}

// ── StepStatus ────────────────────────────────────────────────────────────────

enum class StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,

    /** Non-fatal failure — only possible when Action.continueOnFailure = true. */
    FAILED_IGNORED,

    /** Fatal failure — workflow stops here (unless continueOnFailure = true). */
    FAILED,

    /** Step was skipped because its enabled flag is false. */
    SKIPPED,
    RETRYING,
}

// ── WorkflowStepExecution ─────────────────────────────────────────────────────
//
// One row per step execution attempt.
// Multiple rows for the same stepId = retries.
//
// `inputChecksum` is the SHA-256 of the step's input data.
// If a COMPLETED row exists with the same stepId + inputChecksum,
// the orchestrator skips re-execution and reuses the previous output.
// This is the idempotency guarantee — safe retries, safe manual reprocessing.

data class WorkflowStepExecution(

    val id: String = UUID.randomUUID().toString(),

    val workflowExecutionId: String,

    /** References Action.steps[n].id */
    val stepId: String,

    /** Matches WorkflowStep.executionOrder — used to resume from correct position. */
    val stepIndex: Int,

    val stepName: String,

    val status: StepStatus = StepStatus.PENDING,

    /** Which attempt this is (1-indexed). */
    val attempt: Int = 1,

    /**
     * SHA-256 of the serialised input data passed to this step.
     * Used for idempotency: if a COMPLETED execution with this checksum exists,
     * skip re-execution and reuse [outputSummary].
     */
    val inputChecksum: String? = null,

    /**
     * Human-readable summary of step output for display in the UI.
     * Not the full output — the full output lives in WorkflowExecution.checkpointData.
     * Examples: "1,228 records processed", "File ACH_20260321.ach generated (42 KB)"
     */
    val outputSummary: String? = null,

    val recordsProcessed: Int = 0,

    val errorMessage: String? = null,
    val errorType: String? = null,

    val startedAt: Instant? = null,
    val completedAt: Instant? = null,

) {
    val durationMs: Long?
        get() = if (startedAt != null && completedAt != null) {
            completedAt.toEpochMilli() - startedAt.toEpochMilli()
        } else {
            null
        }
}

// ── WorkflowExecution ─────────────────────────────────────────────────────────
//
// One row per Action execution per WindowInstance.
// This is what makes the engine crash-safe and resumable.
//
// Recovery path (WorkflowRecoveryService on startup):
//   1. Query for WorkflowExecution rows with status IN (RUNNING, RETRYING)
//   2. For each, load the action from the pinned profile version
//   3. Resume from currentStepIndex using checkpointData as input
//
// The checkpointData JSONB field carries the output of the last successfully
// completed step. It is the "hand-off" between steps and the resume point
// after a crash. Analogous to Flink's checkpoint state.

data class WorkflowExecution(

    val id: String = UUID.randomUUID().toString(),

    val windowInstanceId: String,

    val profileId: String,

    /** The profile version pinned at execution time. Stable for replay. */
    val profileVersion: Int,

    /** The action that triggered this workflow. */
    val actionId: String,
    val actionName: String,

    val status: WorkflowStatus = WorkflowStatus.PENDING,

    /**
     * Index of the step currently executing (or the last step that was attempted).
     * Used by WorkflowRecoveryService to resume from the correct position.
     * 0 = not started yet.
     */
    val currentStepIndex: Int = 0,

    /**
     * Serialised output of the last COMPLETED step.
     * Format is determined by the StepExecutor — typically a reference to
     * a temporary record stream or file path rather than the full data.
     *
     * This is the crash-recovery handoff point.
     * On resume, this is passed as StepContext.inputData to the next step.
     */
    val checkpointData: Map<String, Any> = emptyMap(),

    /** Total number of records processed across all steps. Summed from step executions. */
    val totalRecordsProcessed: Int = 0,

    /** How many times the workflow has been attempted (for top-level retry counting). */
    val totalAttempts: Int = 1,

    val errorMessage: String? = null,

    val stepExecutions: List<WorkflowStepExecution> = emptyList(),

    val startedAt: Instant? = null,
    val lastUpdatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,

) {
    val isTerminal: Boolean get() = status in setOf(
        WorkflowStatus.COMPLETED,
        WorkflowStatus.COMPLETED_WITH_ERRORS,
        WorkflowStatus.FAILED,
        WorkflowStatus.CANCELLED,
    )

    val durationMs: Long?
        get() = if (startedAt != null && completedAt != null) {
            completedAt.toEpochMilli() - startedAt.toEpochMilli()
        } else {
            null
        }

    /** Returns the step execution for a given step index, most recent attempt. */
    fun latestStepExecution(stepIndex: Int): WorkflowStepExecution? = stepExecutions
        .filter { it.stepIndex == stepIndex }
        .maxByOrNull { it.attempt }
}
