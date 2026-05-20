package com.transformplatform.api.persistence.adapter

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.api.persistence.entity.WorkflowExecutionJpaEntity
import com.transformplatform.api.persistence.entity.WorkflowStepExecutionJpaEntity
import com.transformplatform.api.persistence.repository.JpaWorkflowExecutionRepository
import com.transformplatform.api.persistence.repository.JpaWorkflowStepExecutionRepository
import com.transformplatform.common.domain.workflow.StepStatus
import com.transformplatform.common.domain.workflow.WorkflowExecution
import com.transformplatform.common.domain.workflow.WorkflowStatus
import com.transformplatform.common.domain.workflow.WorkflowStepExecution
import com.transformplatform.scheduler.repository.WorkflowExecutionRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Component
@Transactional(readOnly = true)
class JpaWorkflowExecutionRepositoryAdapter(
    private val execRepo: JpaWorkflowExecutionRepository,
    private val stepRepo: JpaWorkflowStepExecutionRepository,
    private val objectMapper: ObjectMapper,
) : WorkflowExecutionRepository {

    private val checkpointType = object : TypeReference<Map<String, Any>>() {}

    // ── Write ──────────────────────────────────────────────────────────────────

    @Transactional
    override fun save(execution: WorkflowExecution): WorkflowExecution {
        // Save or update the execution header
        val saved = execRepo.save(execution.toEntity())

        // Upsert all step executions: delete old ones and re-insert
        // (step list is typically small — max ~20 steps per action)
        stepRepo.deleteByExecutionId(execution.id)
        val savedSteps = stepRepo.saveAll(execution.stepExecutions.map { it.toEntity(execution.id) })

        return saved.toDomain(savedSteps)
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    override fun findById(id: String): WorkflowExecution? {
        val entity = execRepo.findById(id).orElse(null) ?: return null
        val steps = stepRepo.findByExecutionIdOrderByStepIndexAscAttemptAsc(id)
        return entity.toDomain(steps)
    }

    override fun findByWindowId(windowId: String): List<WorkflowExecution> {
        val executions = execRepo.findByWindowIdOrderByLastUpdatedAtDesc(windowId)
        return executions.map { exec ->
            val steps = stepRepo.findByExecutionIdOrderByStepIndexAscAttemptAsc(exec.id)
            exec.toDomain(steps)
        }
    }

    override fun findByProfileId(profileId: String, limit: Int): List<WorkflowExecution> {
        val executions = execRepo.findByProfileIdOrderByLastUpdatedAtDesc(
            profileId,
            PageRequest.of(0, limit),
        )
        return executions.map { exec ->
            val steps = stepRepo.findByExecutionIdOrderByStepIndexAscAttemptAsc(exec.id)
            exec.toDomain(steps)
        }
    }

    override fun findByStatus(vararg statuses: WorkflowStatus): List<WorkflowExecution> {
        val statusNames = statuses.map { it.name }
        val executions = execRepo.findByStatusIn(statusNames)
        return executions.map { exec ->
            val steps = stepRepo.findByExecutionIdOrderByStepIndexAscAttemptAsc(exec.id)
            exec.toDomain(steps)
        }
    }

    override fun findAll(limit: Int): List<WorkflowExecution> {
        val executions = execRepo.findAllPaged(PageRequest.of(0, limit))
        return executions.map { exec ->
            val steps = stepRepo.findByExecutionIdOrderByStepIndexAscAttemptAsc(exec.id)
            exec.toDomain(steps)
        }
    }

    override fun findByStatusAndStartedBefore(status: WorkflowStatus, before: Instant): List<WorkflowExecution> {
        val executions = execRepo.findByStatusAndStartedAtBefore(status.name, before)
        return executions.map { exec ->
            val steps = stepRepo.findByExecutionIdOrderByStepIndexAscAttemptAsc(exec.id)
            exec.toDomain(steps)
        }
    }

    // ── Mapping ────────────────────────────────────────────────────────────────

    private fun WorkflowExecution.toEntity() = WorkflowExecutionJpaEntity().apply {
        id = this@toEntity.id
        // Domain model uses `windowInstanceId`; entity column is `window_id`.
        windowId = this@toEntity.windowInstanceId
        profileId = this@toEntity.profileId
        profileVersion = this@toEntity.profileVersion
        actionId = this@toEntity.actionId
        actionName = this@toEntity.actionName
        status = this@toEntity.status.name
        currentStepIndex = this@toEntity.currentStepIndex
        checkpointData = objectMapper.writeValueAsString(this@toEntity.checkpointData)
        totalRecordsProcessed = this@toEntity.totalRecordsProcessed
        totalAttempts = this@toEntity.totalAttempts
        errorMessage = this@toEntity.errorMessage
        startedAt = this@toEntity.startedAt
        lastUpdatedAt = this@toEntity.lastUpdatedAt
        completedAt = this@toEntity.completedAt
    }

    private fun WorkflowStepExecution.toEntity(parentExecutionId: String) = WorkflowStepExecutionJpaEntity().apply {
        id = this@toEntity.id
        // Entity field is `executionId`; domain field is `workflowExecutionId`.
        executionId = parentExecutionId
        stepId = this@toEntity.stepId
        stepIndex = this@toEntity.stepIndex
        stepName = this@toEntity.stepName
        status = this@toEntity.status.name
        attempt = this@toEntity.attempt
        inputChecksum = this@toEntity.inputChecksum
        outputSummary = this@toEntity.outputSummary
        recordsProcessed = this@toEntity.recordsProcessed
        errorMessage = this@toEntity.errorMessage
        errorType = this@toEntity.errorType
        startedAt = this@toEntity.startedAt
        completedAt = this@toEntity.completedAt
    }

    private fun WorkflowExecutionJpaEntity.toDomain(steps: List<WorkflowStepExecutionJpaEntity>) = WorkflowExecution(
        id = id,
        windowInstanceId = windowId, // entity `windowId` → domain `windowInstanceId`
        profileId = profileId,
        profileVersion = profileVersion,
        actionId = actionId,
        actionName = actionName,
        status = WorkflowStatus.valueOf(status),
        currentStepIndex = currentStepIndex,
        checkpointData = objectMapper.readValue(checkpointData, checkpointType),
        totalRecordsProcessed = totalRecordsProcessed,
        totalAttempts = totalAttempts,
        errorMessage = errorMessage,
        stepExecutions = steps.map { it.toDomain() },
        startedAt = startedAt,
        lastUpdatedAt = lastUpdatedAt,
        completedAt = completedAt,
    )

    private fun WorkflowStepExecutionJpaEntity.toDomain() = WorkflowStepExecution(
        id = id,
        workflowExecutionId = executionId, // entity `executionId` → domain `workflowExecutionId`
        stepId = stepId,
        stepIndex = stepIndex,
        stepName = stepName,
        status = StepStatus.valueOf(status),
        attempt = attempt,
        inputChecksum = inputChecksum,
        outputSummary = outputSummary,
        recordsProcessed = recordsProcessed,
        errorMessage = errorMessage,
        errorType = errorType,
        startedAt = startedAt,
        completedAt = completedAt,
    )
}
