package com.transformplatform.scheduler.service

import com.transformplatform.common.domain.profile.Profile
import com.transformplatform.common.domain.trigger.WindowTrigger
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.domain.window.WindowStatus
import com.transformplatform.scheduler.job.WindowOrchestratorJob
import com.transformplatform.scheduler.job.WorkflowMonitorJob
import com.transformplatform.scheduler.repository.ProfileRepository
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import com.transformplatform.scheduler.util.parseCron
import mu.KotlinLogging
import org.quartz.CronScheduleBuilder.cronSchedule
import org.quartz.JobBuilder.newJob
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.TriggerBuilder.newTrigger
import org.quartz.TriggerKey
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZonedDateTime

private val log = KotlinLogging.logger {}

// ── JobDataKeys ───────────────────────────────────────────────────────────────

object JobDataKeys {
    const val PROFILE_ID = "profileId"
    const val PROFILE_VERSION = "profileVersion"
    const val CLIENT_ID = "clientId"
    const val WINDOW_INSTANCE_ID = "windowInstanceId"
}

// ── WindowSchedulingService ───────────────────────────────────────────────────
//
// Manages profile activation and the single Quartz orchestrator job.
//
// Responsibilities:
//   1. Bootstrap: register the single WindowOrchestratorJob in Quartz on startup
//   2. scheduleProfile():   when a profile is ENABLED
//                           → create a PENDING WindowInstance with scheduledOpenAt
//                           (the orchestrator picks it up at the right time)
//   3. unscheduleProfile(): when a profile is DISABLED
//                           → delete the PENDING window; in-flight windows continue
//   4. rescheduleProfile(): when an ENABLED profile's config changes
//                           → recreate the PENDING window with the new nextOpenAt
//
// What this service does NOT do:
//   • Register per-profile Quartz CronTriggers  (removed — that was 2N jobs)
//   • Store nextOpenAt / nextCloseAt on Profile  (removed — those are on WindowInstance)
//
// ┌──────────────────────────────────────────────────────────────────────────┐
// │ Window creation flow                                                     │
// │                                                                          │
// │  scheduleProfile(profile)                                                │
// │    → createNextPendingWindow(profile)                                    │
// │        → WindowInstance(status=PENDING, scheduledOpenAt=<next cron>)    │
// │        → windowRepo.save()                                               │
// │                                                                          │
// │  WindowOrchestratorJob (every 30s)                                       │
// │    → windowRepo.findDueToOpen(now)                                       │
// │        → [PENDING windows where scheduledOpenAt <= now]                  │
// │    → WindowOpenChecker.processWindow()                                   │
// │        → windowStateService.open()                                       │
// │        → windowRepo.setScheduledCloseAt()  (if time-based close)        │
// │        → windowRepo.save(nextPendingWindow)   ← next cycle               │
// └──────────────────────────────────────────────────────────────────────────┘

@Service
class WindowSchedulingService(
    private val scheduler: Scheduler,
    private val profileRepo: ProfileRepository,
    private val windowRepo: WindowInstanceRepository,
    @Value("\${scheduler.orchestrator.cron:0/30 * * * * ?}")
    private val orchestratorCron: String,
    @Value("\${scheduler.monitor.cron:0 0/2 * * * ?}")
    private val monitorCron: String,
) {

    companion object {
        private val ORCHESTRATOR_JOB_KEY = JobKey.jobKey("window-orchestrator", "orchestrator")
        private val ORCHESTRATOR_TRIGGER_KEY = TriggerKey.triggerKey("trigger-orchestrator", "orchestrator")
        private val MONITOR_JOB_KEY = JobKey.jobKey("workflow-monitor", "monitor")
        private val MONITOR_TRIGGER_KEY = TriggerKey.triggerKey("trigger-monitor", "monitor")
    }

    // ── Orchestrator bootstrap ─────────────────────────────────────────────────

    /**
     * Register the single WindowOrchestratorJob in Quartz, if not already present.
     * Called once on application startup via @PostConstruct or ApplicationRunner.
     *
     * This is the ONLY Quartz job registration needed for the entire scheduling system.
     * All per-profile scheduling is driven by the `scheduled_open_at` / `scheduled_close_at`
     * columns on the windows table.
     */
    fun ensureOrchestratorRegistered() {
        if (scheduler.checkExists(ORCHESTRATOR_JOB_KEY)) {
            log.info { "WindowOrchestratorJob already registered" }
            return
        }

        val jobDetail = newJob(WindowOrchestratorJob::class.java)
            .withIdentity(ORCHESTRATOR_JOB_KEY)
            .storeDurably()
            .requestRecovery(true)
            .build()

        val quartzTrigger = newTrigger()
            .withIdentity(ORCHESTRATOR_TRIGGER_KEY)
            .withSchedule(
                cronSchedule(orchestratorCron)
                    .withMisfireHandlingInstructionDoNothing(),
            )
            .build()

        scheduler.scheduleJob(jobDetail, quartzTrigger)
        log.info { "WindowOrchestratorJob registered: cron='$orchestratorCron'" }
    }

    /**
     * Register the WorkflowMonitorJob in Quartz, if not already present.
     * Called alongside ensureOrchestratorRegistered() on application startup.
     *
     * Default schedule: every 2 minutes (0 0/2 * * * ?).
     * Configurable via `scheduler.monitor.cron`.
     *
     * The monitor is idempotent: if it finds nothing to recover it is a no-op.
     * Running it frequently (every 2 min) keeps the maximum orphan-detection lag small.
     */
    fun ensureMonitorRegistered() {
        if (scheduler.checkExists(MONITOR_JOB_KEY)) {
            log.info { "WorkflowMonitorJob already registered" }
            return
        }

        val jobDetail = newJob(WorkflowMonitorJob::class.java)
            .withIdentity(MONITOR_JOB_KEY)
            .storeDurably()
            .requestRecovery(false) // Monitor job is idempotent; Quartz recovery not needed
            .build()

        val quartzTrigger = newTrigger()
            .withIdentity(MONITOR_TRIGGER_KEY)
            .withSchedule(
                cronSchedule(monitorCron)
                    .withMisfireHandlingInstructionDoNothing(),
            )
            .build()

        scheduler.scheduleJob(jobDetail, quartzTrigger)
        log.info { "WorkflowMonitorJob registered: cron='$monitorCron'" }
    }

    // ── Profile activation / deactivation ─────────────────────────────────────

    /**
     * Called when a profile transitions DRAFT/DISABLED → ENABLED.
     *
     * Creates the first PENDING WindowInstance so the orchestrator can pick it up
     * when scheduledOpenAt arrives.
     *
     * Returns the PENDING window, or null for MANUAL-trigger profiles
     * (those only open when explicitly requested).
     */
    fun scheduleProfile(profile: Profile): WindowInstance? {
        log.info { "Scheduling profile: id=${profile.id}, name='${profile.name}'" }
        return createNextPendingWindow(profile)
    }

    /**
     * Called when a profile transitions ENABLED → DISABLED.
     *
     * Deletes the PENDING window (it will never open now).
     * In-flight OPEN/CLOSING windows are left alone — they complete normally.
     */
    fun unscheduleProfile(profileId: String) {
        log.info { "Unscheduling profile: id=$profileId" }

        val pending = windowRepo.findPendingForProfile(profileId)
        if (pending != null) {
            windowRepo.deleteById(pending.id)
            log.info { "Deleted PENDING window ${pending.id} for disabled profile $profileId" }
        }

        log.info { "Profile $profileId unscheduled. In-flight windows will complete normally." }
    }

    /**
     * Called when an ENABLED profile's config is updated.
     * Replaces the existing PENDING window with one using the new trigger config.
     */
    fun rescheduleProfile(profile: Profile) {
        log.info { "Rescheduling profile: id=${profile.id}, version=${profile.version}" }

        // Remove the old PENDING window (uses the previous config)
        windowRepo.findPendingForProfile(profile.id)?.let {
            windowRepo.deleteById(it.id)
            log.debug { "Removed stale PENDING window ${it.id} before rescheduling" }
        }

        // Create a fresh PENDING window with the new scheduledOpenAt
        createNextPendingWindow(profile)

        log.info { "Profile ${profile.id} rescheduled" }
    }

    // ── PENDING window factory ─────────────────────────────────────────────────

    /**
     * Create a PENDING WindowInstance for the next scheduled execution.
     *
     * scheduledOpenAt is computed from the profile's open trigger:
     *   TimeBased  → next cron firing from now
     *   FileArrival/EventCount/SessionGap → null (window opens when the event arrives)
     *   Manual     → null (window opens only on explicit request)
     *   Compound   → earliest time-based sub-trigger firing, or null
     *
     * The PENDING window sits in the windows table until scheduledOpenAt <= now,
     * at which point the WindowOrchestratorJob opens it.
     *
     * Returns null for MANUAL triggers (no pending window pre-created).
     */
    fun createNextPendingWindow(profile: Profile): WindowInstance? {
        val openTrigger = profile.windowConfig.openTrigger

        // MANUAL triggers: no PENDING window pre-created
        if (openTrigger is WindowTrigger.Manual) {
            log.debug { "Profile ${profile.id} has MANUAL trigger — no PENDING window created" }
            return null
        }

        val scheduledOpenAt = computeScheduledOpenAt(openTrigger)

        val pending = WindowInstance(
            profileId = profile.id,
            profileVersion = profile.version,
            status = WindowStatus.PENDING,
            scheduledOpenAt = scheduledOpenAt,
        )

        val saved = windowRepo.save(pending)

        log.info {
            "PENDING window created: id=${saved.id}, profileId=${profile.id}, " +
                "scheduledOpenAt=$scheduledOpenAt"
        }

        return saved
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun computeScheduledOpenAt(openTrigger: WindowTrigger): Instant? = when (openTrigger) {
        is WindowTrigger.TimeBased ->
            nextCronFiring(openTrigger.openCron, openTrigger.timeZone.id)
        is WindowTrigger.Compound ->
            openTrigger.subTriggers
                .filterIsInstance<WindowTrigger.TimeBased>()
                .mapNotNull { nextCronFiring(it.openCron, it.timeZone.id) }
                .minOrNull()
        is WindowTrigger.FileArrival,
        is WindowTrigger.EventCount,
        is WindowTrigger.SessionGap,
        -> null // event-driven; no fixed schedule
        is WindowTrigger.Manual -> null // on-demand only
    }

    private fun nextCronFiring(cronExpression: String, timeZoneId: String): Instant? {
        return try {
            val zone = java.time.ZoneId.of(timeZoneId)
            val expr = parseCron(cronExpression) // accepts both 5-field Unix and 6-field Spring cron
            expr.next(ZonedDateTime.now(zone))?.toInstant()
        } catch (e: Exception) {
            log.warn { "Could not compute next cron firing for '$cronExpression': ${e.message}" }
            null
        }
    }
}
