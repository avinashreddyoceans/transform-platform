package com.transformplatform.scheduler.job

import com.transformplatform.scheduler.service.WindowCloseChecker
import com.transformplatform.scheduler.service.WindowOpenChecker
import mu.KotlinLogging
import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.PersistJobDataAfterExecution
import org.springframework.stereotype.Component
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── WindowOrchestratorJob ─────────────────────────────────────────────────────
//
// THE single Quartz job for the entire window scheduling system.
// Replaces the N×2 per-profile Quartz CronTrigger approach.
//
// Architecture:
//   One job instance, one cron trigger (e.g. every 30 seconds).
//   On every tick it asks:
//     - "Are any profiles due to open a window?" → WindowOpenChecker
//     - "Are any open windows due to close?"      → WindowCloseChecker
//     - "Have any session-gap windows expired?"   → WindowCloseChecker (included above)
//
// How it scales:
//   With 1 profile:    same behaviour as the old per-profile approach.
//   With 1000 profiles: still one job. The DB query is indexed on (status, next_open_at).
//                       Each tick scans only profiles WHERE next_open_at <= NOW() — fast.
//   Multi-node:        SELECT FOR UPDATE SKIP LOCKED in Phase 1a ensures each node
//                       processes a different batch of profiles. No double-opens.
//
// Tick interval:
//   Default: 30 seconds. Configurable via `scheduler.orchestrator.interval-seconds`
//   (set in the Quartz job registration in WindowSchedulingService).
//
//   30s feels coarse for profiles that should open at a specific time (e.g. 08:00:00).
//   In practice:
//   - We set nextOpenAt = exact cron firing time (e.g. 08:00:00)
//   - The orchestrator fires at (say) 08:00:30
//   - The window opens 30s late — acceptable for batch ETL windows
//   - If sub-minute precision matters, reduce the interval to 5s or 10s
//   - The interval is a config knob, not a hard constraint
//
// @DisallowConcurrentExecution:
//   Quartz will NOT start a second execution of this job if the previous tick is
//   still running. This is the in-process guard (one pod). The DB-level guard
//   (SELECT FOR UPDATE SKIP LOCKED) handles the multi-pod case.

@Component
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
class WindowOrchestratorJob(
    private val windowOpenChecker: WindowOpenChecker,
    private val windowCloseChecker: WindowCloseChecker,
) : Job {

    override fun execute(context: JobExecutionContext) {
        val tickStart = Instant.now()
        log.debug { "WindowOrchestratorJob: tick starting at $tickStart" }

        var opened = 0
        var closed = 0

        // ── Check opens ───────────────────────────────────────────────────────
        try {
            opened = windowOpenChecker.checkAndOpen()
        } catch (ex: Exception) {
            // Log and continue — close check must still run even if open check fails
            log.error(ex) { "WindowOrchestratorJob: WindowOpenChecker threw unexpectedly" }
        }

        // ── Check closes ──────────────────────────────────────────────────────
        try {
            closed = windowCloseChecker.checkAndClose()
        } catch (ex: Exception) {
            log.error(ex) { "WindowOrchestratorJob: WindowCloseChecker threw unexpectedly" }
        }

        val elapsed = java.time.Duration.between(tickStart, Instant.now()).toMillis()

        if (opened > 0 || closed > 0) {
            log.info {
                "WindowOrchestratorJob: tick complete in ${elapsed}ms — " +
                    "opened=$opened, closed=$closed"
            }
        } else {
            log.debug { "WindowOrchestratorJob: idle tick in ${elapsed}ms" }
        }
    }
}
