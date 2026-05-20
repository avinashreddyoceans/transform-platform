package com.transformplatform.api.service.workflow

import com.transformplatform.common.domain.workflow.WorkflowStatus
import com.transformplatform.scheduler.repository.WorkflowExecutionRepository
import jakarta.annotation.PostConstruct
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── WorkflowRecoveryService ───────────────────────────────────────────────────
//
// Startup bean that detects workflow executions that were left in an
// intermediate state (RUNNING or RETRYING) due to an application crash or
// ungraceful shutdown.
//
// Phase 1d behaviour:
//   Since Phase 1d uses synchronous in-process execution (no Quartz-backed retry),
//   any execution that is still RUNNING at startup cannot be safely resumed —
//   the in-memory execution context is gone.  We mark these as FAILED with a
//   clear reason and log a warning so operators can reprocess the affected windows
//   via POST /api/windows/{id}/reprocess.
//
// Phase 2 upgrade path:
//   Replace with async resume logic: load checkpointData from the stuck execution,
//   rebuild StepContext, and call WorkflowOrchestratorImpl.resumeFrom(executionId).
//
// This is the crash-recovery logic referenced in the PLAN.md Phase 1d section:
//   "WorkflowRecoveryService — @PostConstruct bean that queries for
//    WorkflowExecution records in RUNNING/RETRYING state at startup"

@Service
class WorkflowRecoveryService(
    private val execRepo: WorkflowExecutionRepository,
) {

    @PostConstruct
    fun recoverStuckExecutions() {
        val stuck = execRepo.findByStatus(WorkflowStatus.RUNNING, WorkflowStatus.RETRYING)

        if (stuck.isEmpty()) {
            log.debug { "WorkflowRecovery: no stuck executions found at startup" }
            return
        }

        log.warn {
            "WorkflowRecovery: found ${stuck.size} execution(s) stuck in RUNNING/RETRYING " +
                "state — marking as FAILED. These windows can be reprocessed via " +
                "POST /api/windows/{id}/reprocess."
        }

        val now = Instant.now()
        var recovered = 0
        var skipped = 0

        for (execution in stuck) {
            try {
                execRepo.save(
                    execution.copy(
                        status = WorkflowStatus.FAILED,
                        errorMessage = "Execution interrupted by application restart. " +
                            "Use POST /api/windows/${execution.windowInstanceId}/reprocess to retry.",
                        lastUpdatedAt = now,
                        completedAt = now,
                    ),
                )
                log.warn {
                    "WorkflowRecovery: marked execution ${execution.id} as FAILED " +
                        "(window=${execution.windowInstanceId}, action='${execution.actionName}')"
                }
                recovered++
            } catch (ex: Exception) {
                log.error(ex) {
                    "WorkflowRecovery: failed to mark execution ${execution.id} as FAILED: ${ex.message}"
                }
                skipped++
            }
        }

        log.warn {
            "WorkflowRecovery: recovery complete — " +
                "marked=$recovered, failed_to_mark=$skipped out of ${stuck.size} total"
        }
    }
}
