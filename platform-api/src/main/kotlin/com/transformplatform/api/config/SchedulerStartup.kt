package com.transformplatform.api.config

import com.transformplatform.scheduler.service.WindowSchedulingService
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

// ── SchedulerStartup ──────────────────────────────────────────────────────────
//
// Registers Quartz jobs that must exist for the system to operate.
// Runs after the context is fully ready (@Order 3 — after DynamicRouteManager's @Order 2).
//
// Two jobs are registered:
//
//  WindowOrchestratorJob  (cron: every 30s, configurable via scheduler.orchestrator.cron)
//    The single scheduler tick: opens due windows, closes expired windows.
//    Quartz persists this job in the JDBC jobstore, so on subsequent restarts
//    ensureOrchestratorRegistered() is a no-op (job already exists in DB).
//
//  WorkflowMonitorJob  (cron: every 2min, configurable via scheduler.monitor.cron)
//    Monitors workflow executions for hung/orphaned runs and auto-retries FAILED
//    executions within budget. See WorkflowMonitorService for full details.
//
// @Order(3): runs after:
//   @Order(1) — StartupValidator (platform-integration): DB + MinIO health check
//   @Order(2) — DynamicRouteManager (platform-integration): Camel routes registered
// This ensures the scheduler sees the correct Camel route state when it first fires.

@Component
@Order(3)
class SchedulerStartup(
    private val windowSchedulingService: WindowSchedulingService,
) {

    @EventListener(ApplicationReadyEvent::class)
    fun registerJobs() {
        log.info { "SchedulerStartup: registering Quartz jobs" }

        windowSchedulingService.ensureOrchestratorRegistered()
        windowSchedulingService.ensureMonitorRegistered()

        log.info { "SchedulerStartup: Quartz jobs registered" }
    }
}
