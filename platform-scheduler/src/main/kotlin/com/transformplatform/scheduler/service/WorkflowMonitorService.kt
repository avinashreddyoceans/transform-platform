package com.transformplatform.scheduler.service

import com.transformplatform.common.domain.window.WindowStatus
import com.transformplatform.common.domain.workflow.WorkflowStatus
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import com.transformplatform.scheduler.repository.WorkflowExecutionRepository
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── WorkflowMonitorService ────────────────────────────────────────────────────
//
// Periodic monitoring agent for the workflow orchestrator.
// Called by WorkflowMonitorJob on a configurable Quartz schedule (default: every 2 min).
//
// Two recovery responsibilities:
//
//  1. ORPHAN DETECTION — catches hung executions the startup crash-recovery misses.
//     WorkflowRecoveryService (PostConstruct) handles the "app crashed mid-step" case.
//     This service handles the "app is alive but a step is permanently blocked" case:
//     e.g. a Camel route that never returns, a coroutine that deadlocked, or a
//     runBlocking{} that is waiting on a cancelled scope.
//     Heuristic: any RUNNING execution older than [stuckThresholdMinutes] is orphaned.
//
//  2. AUTO-RETRY — re-submits FAILED workflow executions that are still within
//     the configured auto-retry budget ([maxAutoRetries]).
//     Only retried for windows that are still OPEN (no point retrying a workflow for
//     a window that has already closed or been superseded).
//     Guards against double-retry: skips if a newer COMPLETED or RUNNING execution
//     already exists for the same window + action.
//
// Phase 2 upgrade path:
//   Replace orphan detection with proper Quartz heartbeat: the orchestrator updates
//   a `lastHeartbeatAt` column every 30 seconds; the monitor checks that column.
//   Replace auto-retry with Quartz SimpleTrigger-backed delayed retry so backoff
//   delays are honoured without blocking a thread.

@Service
class WorkflowMonitorService(
    private val execRepo: WorkflowExecutionRepository,
    private val windowRepo: WindowInstanceRepository,
    private val orchestrator: WorkflowOrchestrator,
    @Value("\${workflow.monitor.stuck-threshold-minutes:10}")
    private val stuckThresholdMinutes: Long = 10,
    @Value("\${workflow.monitor.max-auto-retries:3}")
    private val maxAutoRetries: Int = 3,
) {

    // ── Public API ────────────────────────────────────────────────────────────

    @Transactional
    fun scan(): WorkflowMonitorResult {
        val scanStart = Instant.now()
        log.debug { "WorkflowMonitor: scan starting" }

        val orphaned = recoverOrphaned()
        val retried = retryFailed()

        val elapsed = Duration.between(scanStart, Instant.now()).toMillis()

        if (orphaned > 0 || retried > 0) {
            log.info {
                "WorkflowMonitor: scan complete in ${elapsed}ms — " +
                    "orphansRecovered=$orphaned, autoRetried=$retried"
            }
        } else {
            log.debug { "WorkflowMonitor: scan complete in ${elapsed}ms — nothing to recover" }
        }

        return WorkflowMonitorResult(
            orphanedRecovered = orphaned,
            autoRetried = retried,
            scannedAt = scanStart,
        )
    }

    // ── Orphan detection ──────────────────────────────────────────────────────
    //
    // Any execution still RUNNING after [stuckThresholdMinutes] is considered hung.
    // We mark it FAILED and include the threshold in the error message so operators
    // know why it was killed.

    private fun recoverOrphaned(): Int {
        val threshold = Instant.now().minusSeconds(stuckThresholdMinutes * 60)
        val stuck = execRepo.findByStatusAndStartedBefore(WorkflowStatus.RUNNING, threshold)

        if (stuck.isEmpty()) return 0

        log.warn {
            "WorkflowMonitor: found ${stuck.size} orphaned RUNNING execution(s) " +
                "(running > $stuckThresholdMinutes minutes). Marking as FAILED."
        }

        val now = Instant.now()
        var recovered = 0

        for (exec in stuck) {
            try {
                execRepo.save(
                    exec.copy(
                        status = WorkflowStatus.FAILED,
                        errorMessage = "Execution orphaned: still RUNNING after " +
                            "$stuckThresholdMinutes minutes at step index ${exec.currentStepIndex}. " +
                            "Auto-marked by WorkflowMonitorService. " +
                            "Use POST /api/windows/${exec.windowInstanceId}/reprocess to retry.",
                        lastUpdatedAt = now,
                        completedAt = now,
                    ),
                )
                log.warn {
                    "WorkflowMonitor: orphaned execution ${exec.id} marked FAILED " +
                        "(window=${exec.windowInstanceId}, action='${exec.actionName}', " +
                        "stuckAt=step ${exec.currentStepIndex})"
                }
                recovered++
            } catch (ex: Exception) {
                log.error(ex) {
                    "WorkflowMonitor: failed to mark orphaned execution ${exec.id} as FAILED"
                }
            }
        }

        return recovered
    }

    // ── Auto-retry ────────────────────────────────────────────────────────────
    //
    // For each FAILED execution where totalAttempts < maxAutoRetries:
    //   • The window must still be OPEN (retrying a CLOSED window's ON_FILE_ARRIVED
    //     makes no sense — the window cycle is over)
    //   • No newer COMPLETED or RUNNING execution may exist for the same
    //     window + action (guards against retrying something that already succeeded
    //     in a subsequent attempt)
    //
    // The retry is done by calling orchestrator.executeOnFileArrived() again.
    // The orchestrator creates a brand-new WorkflowExecution row with
    // totalAttempts = (previous.totalAttempts + 1).
    //
    // Note: orchestrator.executeOnFileArrived() does not currently accept an
    // "attempt count" parameter. The totalAttempts increment is tracked here by
    // comparing execution rows for the same window+action rather than storing it
    // on the execution itself, which keeps the orchestrator interface simple.

    private fun retryFailed(): Int {
        val failed = execRepo.findByStatus(WorkflowStatus.FAILED)
        if (failed.isEmpty()) return 0

        var retried = 0

        for (exec in failed) {
            // Skip if this execution has already been retried too many times.
            // We count all executions for this window+action to determine total attempts.
            val allForWindow = execRepo.findByWindowId(exec.windowInstanceId)
            val attemptsForAction = allForWindow.count { it.actionId == exec.actionId }

            if (attemptsForAction >= maxAutoRetries) {
                log.debug {
                    "WorkflowMonitor: skipping auto-retry for execution ${exec.id} — " +
                        "$attemptsForAction attempt(s) already made (max=$maxAutoRetries)"
                }
                continue
            }

            // Skip if window is no longer open (no point re-triggering ON_FILE_ARRIVED
            // for a window that has since closed or been superseded)
            val window = windowRepo.findById(exec.windowInstanceId)
            if (window == null || window.status != WindowStatus.OPEN) {
                log.debug {
                    "WorkflowMonitor: skipping auto-retry for execution ${exec.id} — " +
                        "window ${exec.windowInstanceId} is ${window?.status ?: "not found"}"
                }
                continue
            }

            // Skip if a newer execution for the same action already succeeded or is running
            val latestForAction = allForWindow
                .filter { it.actionId == exec.actionId }
                .maxByOrNull { it.startedAt ?: Instant.EPOCH }

            if (latestForAction != null && latestForAction.id != exec.id) {
                if (latestForAction.status in setOf(
                        WorkflowStatus.COMPLETED,
                        WorkflowStatus.COMPLETED_WITH_ERRORS,
                        WorkflowStatus.RUNNING,
                    )
                ) {
                    log.debug {
                        "WorkflowMonitor: skipping auto-retry for execution ${exec.id} — " +
                            "a later execution ${latestForAction.id} is already ${latestForAction.status}"
                    }
                    continue
                }
            }

            log.info {
                "WorkflowMonitor: auto-retrying window ${exec.windowInstanceId} " +
                    "(action='${exec.actionName}', attempt ${attemptsForAction + 1}/$maxAutoRetries)"
            }

            try {
                orchestrator.executeOnFileArrived(exec.windowInstanceId)
                retried++
                log.info {
                    "WorkflowMonitor: auto-retry succeeded for window ${exec.windowInstanceId}"
                }
            } catch (ex: Exception) {
                log.error(ex) {
                    "WorkflowMonitor: auto-retry failed for window ${exec.windowInstanceId}: ${ex.message}"
                }
                // Don't rethrow — continue scanning other failed executions
            }
        }

        return retried
    }
}

// ── WorkflowMonitorResult ─────────────────────────────────────────────────────

data class WorkflowMonitorResult(
    val orphanedRecovered: Int,
    val autoRetried: Int,
    val scannedAt: Instant,
)
