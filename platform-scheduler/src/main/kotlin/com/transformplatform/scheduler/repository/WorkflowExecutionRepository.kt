package com.transformplatform.scheduler.repository

import com.transformplatform.common.domain.workflow.WorkflowExecution
import com.transformplatform.common.domain.workflow.WorkflowStatus
import java.time.Instant

// ── WorkflowExecutionRepository ───────────────────────────────────────────────
//
// Persistence interface for WorkflowExecution (and its embedded step executions).
//
// Access patterns:
//   1. Crash-recovery on startup — find all RUNNING/RETRYING executions to resume
//   2. Action chain start       — create a new execution for a triggered action
//   3. Step completion          — update status, checkpoint, step executions
//   4. Admin/UI queries         — list by window, profile, status

interface WorkflowExecutionRepository {

    // ── Write ─────────────────────────────────────────────────────────────────

    fun save(execution: WorkflowExecution): WorkflowExecution

    // ── Read ──────────────────────────────────────────────────────────────────

    fun findById(id: String): WorkflowExecution?

    /**
     * All executions for a specific window (the full action chain context).
     * Used to display execution history in the window detail view.
     */
    fun findByWindowId(windowId: String): List<WorkflowExecution>

    /**
     * All executions for a profile, newest first.
     * Used for profile execution history / admin view.
     */
    fun findByProfileId(profileId: String, limit: Int = 50): List<WorkflowExecution>

    /**
     * Executions in the given status(es).
     * Primary use: crash-recovery on startup — query for RUNNING/RETRYING.
     */
    fun findByStatus(vararg statuses: WorkflowStatus): List<WorkflowExecution>

    /**
     * All executions, newest first (for admin overview).
     */
    fun findAll(limit: Int = 100): List<WorkflowExecution>

    /**
     * Executions in the given status that started before [before].
     *
     * Used by WorkflowMonitorService to detect orphaned/stuck executions:
     *   findByStatusAndStartedBefore(RUNNING, now - 10min)
     * returns all RUNNING executions that have been running longer than 10 minutes,
     * which indicates a hung process (not a crash — crashes are caught at startup by
     * WorkflowRecoveryService).
     */
    fun findByStatusAndStartedBefore(status: WorkflowStatus, before: Instant): List<WorkflowExecution>
}
