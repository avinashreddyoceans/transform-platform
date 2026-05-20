package com.transformplatform.scheduler.job

import com.transformplatform.scheduler.service.WorkflowMonitorService
import mu.KotlinLogging
import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

// ── WorkflowMonitorJob ────────────────────────────────────────────────────────
//
// Quartz job that invokes WorkflowMonitorService.scan() on a regular schedule
// (default: every 2 minutes, configurable via `scheduler.monitor.cron`).
//
// Responsibilities per tick:
//   1. Detect RUNNING executions older than the stuck-threshold → mark FAILED
//   2. Find FAILED executions within the auto-retry budget → re-submit
//
// @DisallowConcurrentExecution:
//   Quartz will not start a second scan if the previous one is still running.
//   This prevents multiple concurrent auto-retry submissions for the same window.
//
// Relationship to WindowOrchestratorJob:
//   These are two independent Quartz jobs. The orchestrator manages window
//   open/close transitions; the monitor manages execution health and recovery.
//   They run on different schedules and do not share state.

@Component
@DisallowConcurrentExecution
class WorkflowMonitorJob(
    private val monitorService: WorkflowMonitorService,
) : Job {

    override fun execute(context: JobExecutionContext) {
        log.debug { "WorkflowMonitorJob: tick — calling WorkflowMonitorService.scan()" }
        try {
            val result = monitorService.scan()
            if (result.orphanedRecovered > 0 || result.autoRetried > 0) {
                log.info {
                    "WorkflowMonitorJob: scan complete — " +
                        "orphanedRecovered=${result.orphanedRecovered}, " +
                        "autoRetried=${result.autoRetried}"
                }
            }
        } catch (ex: Exception) {
            // Catch-all: never let an exception propagate out of execute().
            // Quartz would mark the trigger as misfired and may disable the job.
            log.error(ex) { "WorkflowMonitorJob: unexpected error during scan — ${ex.message}" }
        }
    }
}
