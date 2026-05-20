package com.transformplatform.api.controller.workflow

import com.transformplatform.common.domain.workflow.StepStatus
import com.transformplatform.common.domain.workflow.WorkflowExecution
import com.transformplatform.common.domain.workflow.WorkflowStatus
import com.transformplatform.common.domain.workflow.WorkflowStepExecution
import java.time.Instant

// ── WorkflowExecution DTOs ────────────────────────────────────────────────────

data class WorkflowExecutionSummaryResponse(
    val id: String,
    val windowInstanceId: String,
    val profileId: String,
    val profileVersion: Int,
    val actionId: String,
    val actionName: String,
    val status: WorkflowStatus,
    val currentStepIndex: Int,
    val totalRecordsProcessed: Int,
    val totalAttempts: Int,
    val errorMessage: String?,
    val stepCount: Int,
    val startedAt: Instant?,
    val lastUpdatedAt: Instant,
    val completedAt: Instant?,
    val durationMs: Long?,
)

data class WorkflowExecutionDetailResponse(
    val id: String,
    val windowInstanceId: String,
    val profileId: String,
    val profileVersion: Int,
    val actionId: String,
    val actionName: String,
    val status: WorkflowStatus,
    val currentStepIndex: Int,
    val checkpointData: Map<String, Any>,
    val totalRecordsProcessed: Int,
    val totalAttempts: Int,
    val errorMessage: String?,
    val stepExecutions: List<WorkflowStepExecutionResponse>,
    val startedAt: Instant?,
    val lastUpdatedAt: Instant,
    val completedAt: Instant?,
    val durationMs: Long?,
)

data class WorkflowStepExecutionResponse(
    val id: String,
    val stepId: String,
    val stepIndex: Int,
    val stepName: String,
    val status: StepStatus,
    val attempt: Int,
    val inputChecksum: String?,
    val outputSummary: String?,
    val recordsProcessed: Int,
    val errorMessage: String?,
    val errorType: String?,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val durationMs: Long?,
)

// ── Mappers ───────────────────────────────────────────────────────────────────

fun WorkflowExecution.toSummary() = WorkflowExecutionSummaryResponse(
    id = id,
    windowInstanceId = windowInstanceId,
    profileId = profileId,
    profileVersion = profileVersion,
    actionId = actionId,
    actionName = actionName,
    status = status,
    currentStepIndex = currentStepIndex,
    totalRecordsProcessed = totalRecordsProcessed,
    totalAttempts = totalAttempts,
    errorMessage = errorMessage,
    stepCount = stepExecutions.size,
    startedAt = startedAt,
    lastUpdatedAt = lastUpdatedAt,
    completedAt = completedAt,
    durationMs = durationMs,
)

fun WorkflowExecution.toDetail() = WorkflowExecutionDetailResponse(
    id = id,
    windowInstanceId = windowInstanceId,
    profileId = profileId,
    profileVersion = profileVersion,
    actionId = actionId,
    actionName = actionName,
    status = status,
    currentStepIndex = currentStepIndex,
    checkpointData = checkpointData,
    totalRecordsProcessed = totalRecordsProcessed,
    totalAttempts = totalAttempts,
    errorMessage = errorMessage,
    stepExecutions = stepExecutions.map { it.toResponse() },
    startedAt = startedAt,
    lastUpdatedAt = lastUpdatedAt,
    completedAt = completedAt,
    durationMs = durationMs,
)

fun WorkflowStepExecution.toResponse() = WorkflowStepExecutionResponse(
    id = id,
    stepId = stepId,
    stepIndex = stepIndex,
    stepName = stepName,
    status = status,
    attempt = attempt,
    inputChecksum = inputChecksum,
    outputSummary = outputSummary,
    recordsProcessed = recordsProcessed,
    errorMessage = errorMessage,
    errorType = errorType,
    startedAt = startedAt,
    completedAt = completedAt,
    durationMs = durationMs,
)
