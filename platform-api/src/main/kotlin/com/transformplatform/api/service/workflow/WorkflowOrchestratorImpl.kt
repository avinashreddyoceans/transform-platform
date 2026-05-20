package com.transformplatform.api.service.workflow

import com.transformplatform.api.service.SpecService
import com.transformplatform.common.domain.action.Action
import com.transformplatform.common.domain.action.ActionCondition
import com.transformplatform.common.domain.action.StepType
import com.transformplatform.common.domain.action.WorkflowStep
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.domain.workflow.StepStatus
import com.transformplatform.common.domain.workflow.WorkflowExecution
import com.transformplatform.common.domain.workflow.WorkflowStatus
import com.transformplatform.common.domain.workflow.WorkflowStepExecution
import com.transformplatform.core.pipeline.DestinationType
import com.transformplatform.core.pipeline.PipelineDestination
import com.transformplatform.core.pipeline.PipelineRequest
import com.transformplatform.core.pipeline.TransformationPipeline
import com.transformplatform.integration.storage.S3ArchivalService
import com.transformplatform.scheduler.repository.ProfileRepository
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import com.transformplatform.scheduler.repository.WorkflowExecutionRepository
import com.transformplatform.scheduler.service.WorkflowOrchestrator
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── WorkflowOrchestratorImpl ──────────────────────────────────────────────────
//
// Phase 1d implementation: synchronous, in-process execution using
// TransformationPipeline. Each action's steps are executed in order;
// step output (record counts, summaries) is recorded in WorkflowExecution rows.
//
// For PARSE_FILE steps the pipeline reads the file from MinIO via S3ArchivalService
// using the FileHandle.remotePath stored on the window's arrivedFileHandles list.
// The pipeline also handles VALIDATE and NOTIFY (Kafka publish) internally, so
// the VALIDATE and NOTIFY step executors become lightweight wrappers that report
// success without duplicating work.
//
// Phase 2 will replace this with async Quartz-backed execution, retry handling,
// and proper checkpoint/resume logic.

@Service
@Transactional
class WorkflowOrchestratorImpl(
    private val windowRepo: WindowInstanceRepository,
    private val profileRepo: ProfileRepository,
    private val workflowRepo: WorkflowExecutionRepository,
    private val specService: SpecService,
    private val pipeline: TransformationPipeline,
    private val s3Service: S3ArchivalService,
) : WorkflowOrchestrator {

    // ── Public interface ──────────────────────────────────────────────────────

    override fun executeClosingActions(windowInstanceId: String): Int =
        executeActionsForCondition(windowInstanceId, ActionCondition.ON_CLOSING)

    override fun executeOnFileArrived(windowInstanceId: String): Int =
        executeActionsForCondition(windowInstanceId, ActionCondition.ON_FILE_ARRIVED)

    // ── Core execution logic ──────────────────────────────────────────────────

    private fun executeActionsForCondition(windowInstanceId: String, condition: ActionCondition): Int {
        val window = windowRepo.findById(windowInstanceId)
        if (window == null) {
            log.warn { "WorkflowOrchestrator: window $windowInstanceId not found — skipping" }
            return 0
        }

        val profile = profileRepo.findById(window.profileId)
        if (profile == null) {
            log.warn { "WorkflowOrchestrator: profile ${window.profileId} not found — skipping" }
            return 0
        }

        val matchingActions = profile.actions
            .filter { it.enabled && it.condition == condition }
            .sortedBy { it.executionOrder }

        if (matchingActions.isEmpty()) {
            log.debug {
                "WorkflowOrchestrator: no $condition actions for window $windowInstanceId — skipping"
            }
            return 0
        }

        log.info {
            "WorkflowOrchestrator: executing ${matchingActions.size} $condition action(s) " +
                "for window $windowInstanceId"
        }

        var totalRecords = 0
        for (action in matchingActions) {
            try {
                totalRecords += executeAction(action, window, profile.version)
            } catch (ex: Exception) {
                log.error(ex) {
                    "WorkflowOrchestrator: action '${action.name}' failed for window $windowInstanceId"
                }
                if (!action.continueOnFailure) {
                    // Fire ON_ERROR actions if any exist
                    runCatching {
                        executeActionsForCondition(windowInstanceId, ActionCondition.ON_ERROR)
                    }
                    throw ex
                }
                log.warn {
                    "WorkflowOrchestrator: action '${action.name}' failed but continueOnFailure=true"
                }
            }
        }

        log.info {
            "WorkflowOrchestrator: $condition actions done for window $windowInstanceId, " +
                "totalRecords=$totalRecords"
        }
        return totalRecords
    }

    private fun executeAction(action: Action, window: WindowInstance, profileVersion: Int): Int {
        val startedAt = Instant.now()

        val execution = workflowRepo.save(
            WorkflowExecution(
                windowInstanceId = window.id,
                profileId = window.profileId,
                profileVersion = profileVersion,
                actionId = action.id,
                actionName = action.name,
                status = WorkflowStatus.RUNNING,
                startedAt = startedAt,
                lastUpdatedAt = startedAt,
            ),
        )

        log.info {
            "WorkflowOrchestrator: starting action '${action.name}' " +
                "(id=${execution.id}, window=${window.id})"
        }

        val stepExecutions = mutableListOf<WorkflowStepExecution>()
        var totalRecords = 0
        // Context passes pipeline results between steps
        val ctx = StepContext(window = window)

        try {
            for (step in action.enabledSteps) {
                val stepExec = executeStepWithRetry(step, ctx, execution.id)
                stepExecutions.add(stepExec)
                totalRecords += stepExec.recordsProcessed

                // Checkpoint: persist progress after each step so the monitor can
                // detect exactly where a stuck execution was when it timed out.
                workflowRepo.save(
                    execution.copy(
                        status = WorkflowStatus.RUNNING,
                        currentStepIndex = step.executionOrder,
                        totalRecordsProcessed = totalRecords,
                        checkpointData = ctx.toCheckpoint(),
                        stepExecutions = stepExecutions.toList(),
                        lastUpdatedAt = Instant.now(),
                    ),
                )

                if (stepExec.status == StepStatus.FAILED) {
                    if (!action.continueOnFailure) {
                        // Save FAILED execution and re-throw
                        workflowRepo.save(
                            execution.copy(
                                status = WorkflowStatus.FAILED,
                                currentStepIndex = step.executionOrder,
                                totalRecordsProcessed = totalRecords,
                                errorMessage = stepExec.errorMessage,
                                stepExecutions = stepExecutions,
                                lastUpdatedAt = Instant.now(),
                                completedAt = Instant.now(),
                            ),
                        )
                        error(stepExec.errorMessage ?: "Step '${step.name}' failed")
                    }
                }
            }

            val completedStatus = if (stepExecutions.any { it.status == StepStatus.FAILED }) {
                WorkflowStatus.COMPLETED_WITH_ERRORS
            } else {
                WorkflowStatus.COMPLETED
            }

            workflowRepo.save(
                execution.copy(
                    status = completedStatus,
                    totalRecordsProcessed = totalRecords,
                    stepExecutions = stepExecutions,
                    lastUpdatedAt = Instant.now(),
                    completedAt = Instant.now(),
                ),
            )

            log.info {
                "WorkflowOrchestrator: action '${action.name}' COMPLETED " +
                    "(status=$completedStatus, records=$totalRecords)"
            }
        } catch (ex: Exception) {
            // Persist FAILED state (if not already saved)
            runCatching {
                workflowRepo.save(
                    execution.copy(
                        status = WorkflowStatus.FAILED,
                        totalRecordsProcessed = totalRecords,
                        errorMessage = ex.message,
                        stepExecutions = stepExecutions,
                        lastUpdatedAt = Instant.now(),
                        completedAt = Instant.now(),
                    ),
                )
            }
            throw ex
        }

        return totalRecords
    }

    // ── Step dispatch with retry ──────────────────────────────────────────────
    //
    // Wraps executeStep() with the step's RetryPolicy.
    //
    // Phase 1d behaviour:
    //   Retries are immediate (no sleep between attempts). Sleeping inside a
    //   @Transactional method would hold the DB connection open — unacceptable.
    //   Short transient errors (e.g. a momentary MinIO blip) will be caught by the
    //   immediate retry. Longer outages are handled by WorkflowMonitorService, which
    //   re-submits the entire workflow after the FAILED execution is detected.
    //
    // Phase 2 upgrade:
    //   Replace with Quartz SimpleTrigger-backed async retry so each attempt is
    //   a separate transaction with proper backoff delay.
    //
    // Retryable error matching:
    //   If retryPolicy.retryableErrors is non-empty, only exceptions whose
    //   simple class name appears in the list trigger a retry. All others are
    //   immediately fatal (saves attempting retries that cannot succeed, e.g.
    //   a missing-config IllegalStateException).

    private fun executeStepWithRetry(step: WorkflowStep, ctx: StepContext, executionId: String): WorkflowStepExecution {
        val policy = step.retryPolicy
        var lastExec: WorkflowStepExecution? = null

        for (attempt in 1..policy.maxAttempts) {
            val exec = executeStep(step, ctx, executionId, attempt)

            if (exec.status == StepStatus.COMPLETED) {
                if (attempt > 1) {
                    log.info {
                        "WorkflowOrchestrator: step '${step.name}' succeeded on attempt $attempt/${policy.maxAttempts}"
                    }
                }
                return exec
            }

            // Determine whether this failure is retryable
            val isRetryable = policy.retryableErrors.isEmpty() ||
                policy.retryableErrors.any { it.equals(exec.errorType, ignoreCase = true) }

            if (!isRetryable) {
                log.warn {
                    "WorkflowOrchestrator: step '${step.name}' failed with non-retryable " +
                        "error type '${exec.errorType}' — not retrying"
                }
                return exec
            }

            lastExec = exec

            if (attempt < policy.maxAttempts) {
                log.warn {
                    "WorkflowOrchestrator: step '${step.name}' attempt $attempt/${policy.maxAttempts} " +
                        "FAILED (${exec.errorMessage}) — retrying immediately"
                }
            }
        }

        // All attempts exhausted
        log.error {
            "WorkflowOrchestrator: step '${step.name}' FAILED after ${policy.maxAttempts} attempt(s): " +
                "${lastExec?.errorMessage}"
        }
        return lastExec!!
    }

    // ── Step dispatch ─────────────────────────────────────────────────────────

    private fun executeStep(step: WorkflowStep, ctx: StepContext, executionId: String, attempt: Int = 1): WorkflowStepExecution {
        val startedAt = Instant.now()

        log.info {
            "WorkflowOrchestrator: step '${step.name}' (${step.stepType}) starting " +
                "for execution $executionId"
        }

        return try {
            val (records, summary) = when (step.stepType) {
                StepType.PARSE_FILE -> executeParseFile(step, ctx)
                StepType.VALIDATE -> executeValidate(step, ctx)
                StepType.NOTIFY -> executeNotify(step, ctx)
                else -> {
                    log.warn { "WorkflowOrchestrator: step type ${step.stepType} not implemented, skipping" }
                    Pair(0, "Step type ${step.stepType} not yet implemented — skipped")
                }
            }

            log.info {
                "WorkflowOrchestrator: step '${step.name}' COMPLETED " +
                    "(records=$records, summary=$summary, attempt=$attempt)"
            }

            WorkflowStepExecution(
                workflowExecutionId = executionId,
                stepId = step.id,
                stepIndex = step.executionOrder,
                stepName = step.name,
                status = StepStatus.COMPLETED,
                attempt = attempt,
                recordsProcessed = records,
                outputSummary = summary,
                startedAt = startedAt,
                completedAt = Instant.now(),
            )
        } catch (ex: Exception) {
            log.error(ex) { "WorkflowOrchestrator: step '${step.name}' FAILED (attempt $attempt): ${ex.message}" }
            WorkflowStepExecution(
                workflowExecutionId = executionId,
                stepId = step.id,
                stepIndex = step.executionOrder,
                stepName = step.name,
                status = StepStatus.FAILED,
                attempt = attempt,
                errorMessage = ex.message,
                errorType = ex::class.simpleName,
                startedAt = startedAt,
                completedAt = Instant.now(),
            )
        }
    }

    // ── PARSE_FILE ────────────────────────────────────────────────────────────
    //
    // Reads the file from MinIO (via S3ArchivalService.retrieve) using the
    // storage key stored in the first matching FileHandle on the window.
    // Runs the full TransformationPipeline (parse → validate → Kafka).
    // Stores the result record count in ctx for downstream steps.

    private fun executeParseFile(step: WorkflowStep, ctx: StepContext): Pair<Int, String> {
        val fileSpecId = step.requireConfig("fileSpecId")
        val spec = specService.loadSpec(fileSpecId)

        val fileHandle = ctx.window.arrivedFileHandles.firstOrNull()
            ?: throw IllegalStateException(
                "PARSE_FILE: no arrived files on window ${ctx.window.id}. " +
                    "File must arrive before this action can execute.",
            )

        // Determine the topic from step config, fall back to "transform.records"
        val kafkaTopic = step.optionalConfig("topic") ?: "bank.statement.entries"

        log.info {
            "PARSE_FILE: reading '${fileHandle.fileName}' from MinIO key '${fileHandle.remotePath}', " +
                "spec=$fileSpecId, topic=$kafkaTopic"
        }

        val stream = s3Service.retrieve(fileHandle.remotePath)

        val result = runBlocking {
            pipeline.execute(
                PipelineRequest(
                    spec = spec,
                    inputStream = stream,
                    fileName = fileHandle.fileName,
                    destination = PipelineDestination(
                        type = DestinationType.KAFKA_TOPIC,
                        kafkaTopic = kafkaTopic,
                    ),
                    skipInvalidRecords = step.configBool("failOnParseError").not(),
                ),
            )
        }

        ctx.parsedRecords = result.successfulRecords.toInt()
        ctx.failedRecords = result.failedRecords.toInt()
        ctx.totalRecords = result.totalRecords.toInt()
        ctx.pipelineStatus = result.status.name

        val summary = "${result.successfulRecords}/${result.totalRecords} records parsed from " +
            "${fileHandle.fileName} → topic=$kafkaTopic (failed=${result.failedRecords})"

        return Pair(result.successfulRecords.toInt(), summary)
    }

    // ── VALIDATE ──────────────────────────────────────────────────────────────
    //
    // Phase 1d: validation was already performed by TransformationPipeline in
    // the PARSE_FILE step. This step reports the validation outcome.

    private fun executeValidate(step: WorkflowStep, ctx: StepContext): Pair<Int, String> {
        val totalParsed = ctx.parsedRecords
        val failed = ctx.failedRecords

        if (totalParsed == 0 && failed == 0) {
            // PARSE_FILE hasn't run (standalone VALIDATE without prior PARSE_FILE)
            return Pair(0, "No records in context — PARSE_FILE should run before VALIDATE")
        }

        val rejectOnError = step.configBool("rejectOnError", false)
        if (rejectOnError && failed > 0) {
            throw IllegalStateException(
                "VALIDATE: $failed record(s) failed validation and rejectOnError=true",
            )
        }

        val summary = "Validated: $totalParsed records ($failed failed, $totalParsed valid)"
        return Pair(totalParsed, summary)
    }

    // ── NOTIFY ────────────────────────────────────────────────────────────────
    //
    // Phase 1d: Kafka publish was already handled by the PARSE_FILE pipeline.
    // If NOTIFY is configured for a different channel (WEBHOOK), it is not yet
    // implemented. For KAFKA channel, report the count from context.

    private fun executeNotify(step: WorkflowStep, ctx: StepContext): Pair<Int, String> {
        val channel = step.optionalConfig("channel") ?: "KAFKA"

        return when (channel.uppercase()) {
            "KAFKA" -> {
                // Kafka publish was done by PARSE_FILE pipeline; just report the result
                val records = ctx.parsedRecords
                Pair(records, "NOTIFY via Kafka: $records record(s) already published by PARSE_FILE step")
            }
            "WEBHOOK" -> {
                // Phase 2: implement HTTP webhook delivery
                log.warn { "NOTIFY: WEBHOOK channel not yet implemented for step '${step.name}'" }
                Pair(0, "WEBHOOK delivery not yet implemented — skipped")
            }
            else -> {
                log.warn { "NOTIFY: unknown channel '$channel' for step '${step.name}'" }
                Pair(0, "Unknown channel '$channel' — skipped")
            }
        }
    }
}

// ── StepContext ───────────────────────────────────────────────────────────────
//
// Mutable context passed between step executors within a single action execution.
// Carries the output of each step so subsequent steps can read it.
// This is the in-process analogue of WorkflowExecution.checkpointData.

internal data class StepContext(
    val window: WindowInstance,
    var parsedRecords: Int = 0,
    var failedRecords: Int = 0,
    var totalRecords: Int = 0,
    var pipelineStatus: String = "",
) {
    /**
     * Serialise the current context state into the WorkflowExecution.checkpointData map.
     * Written after each step completes so WorkflowMonitorService can see exactly where
     * a hung/orphaned execution stopped.
     *
     * Phase 2: this data will also be used to resume execution from [currentStepIndex]
     * without re-running already-completed steps.
     */
    fun toCheckpoint(): Map<String, Any> = buildMap {
        put("parsedRecords", parsedRecords)
        put("failedRecords", failedRecords)
        put("totalRecords", totalRecords)
        if (pipelineStatus.isNotEmpty()) put("pipelineStatus", pipelineStatus)
    }
}
