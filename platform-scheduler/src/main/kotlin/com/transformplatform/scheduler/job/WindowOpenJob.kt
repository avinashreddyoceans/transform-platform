package com.transformplatform.scheduler.job

import com.transformplatform.common.domain.trigger.WindowTrigger
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import com.transformplatform.scheduler.service.JobDataKeys
import com.transformplatform.scheduler.service.WindowSchedulingService
import com.transformplatform.scheduler.service.WindowStateService
import mu.KotlinLogging
import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.PersistJobDataAfterExecution
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

// ── WindowOpenJob ─────────────────────────────────────────────────────────────
//
// MANUAL / AD-HOC window open entry point.
//
// In the database-driven scheduling model, the primary open path is:
//   WindowOrchestratorJob → WindowOpenChecker → WindowStateService.open()
//
// This job is retained for two use cases:
//   1. Emergency / manual override: operator fires this job directly from
//      the Quartz management UI or admin API to force-open a window outside
//      the normal schedule.
//   2. Testing / integration tests: trigger an open without waiting for the
//      orchestrator tick interval.
//
// It is NOT registered as a CronTrigger by WindowSchedulingService anymore.
// The orchestrator handles all scheduled opens.
//
// What it does, in order:
//   1. Load the profile from the repository (uses pinned version from JobDataMap)
//   2. Find the PENDING window created at scheduling time
//      If not found (e.g. after recovery): create a new one on-the-fly
//   3. Transition PENDING → OPEN via WindowStateService (overlap guard runs here)
//   4. If duration-based close: schedule a one-shot WindowCloseJob
//   5. Create the next PENDING window for the next cycle
//   6. Fire ON_OPEN action chain (WorkflowOrchestrator — Phase 1d stub for now)
//
// @DisallowConcurrentExecution: Quartz will not run a second instance of this
//   job while one is already running. Combined with the overlap guard in
//   WindowStateService, this guarantees no two windows for the same profile
//   can be open simultaneously.

@Component
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
class WindowOpenJob(
    private val windowRepo: WindowInstanceRepository,
    private val windowStateService: WindowStateService,
    private val windowSchedulingService: WindowSchedulingService,
    // Phase 1d: private val workflowOrchestrator: WorkflowOrchestrator,
    // Phase 0:  private val profileRepository: ProfileRepository,  -- stub for now
) : Job {

    override fun execute(context: JobExecutionContext) {
        val jobData = context.mergedJobDataMap
        val profileId = jobData.getString(JobDataKeys.PROFILE_ID)
        val profileVersion = jobData.getInt(JobDataKeys.PROFILE_VERSION)
        val clientId = jobData.getString(JobDataKeys.CLIENT_ID) ?: ""

        log.info { "WindowOpenJob fired: profileId=$profileId, version=$profileVersion" }

        // ── Step 1: Load profile ─────────────────────────────────────────────
        // TODO Phase 0: val profile = profileRepository.findByIdAndVersion(profileId, profileVersion)
        //              ?: profileRepository.findById(profileId)
        //              ?: run { log.error { "Profile $profileId not found — skipping open" }; return }
        //
        // For now we build a stub profile reference from job data.
        // The full implementation will load the real Profile entity.
        val profileStub = ProfileStub(profileId, profileVersion, clientId)

        // ── Step 2: Find or create PENDING window ────────────────────────────
        val pendingWindow = windowRepo.findPendingForProfile(profileId)
            ?: run {
                // Recovery path: no pending window (e.g. first run after server restart
                // between scheduling and the cron firing). Create one now.
                log.warn {
                    "No PENDING window found for profile $profileId at open time. " +
                        "Creating on-the-fly (recovery path)."
                }
                windowRepo.save(
                    com.transformplatform.common.domain.window.WindowInstance(
                        profileId = profileId,
                        profileVersion = profileVersion,
                        status = com.transformplatform.common.domain.window.WindowStatus.PENDING,
                    ),
                )
            }

        // ── Step 3: PENDING → OPEN (overlap guard runs inside) ──────────────
        // TODO Phase 0: pass real windowConfig from profile
        val openedWindow = windowStateService.open(
            windowInstanceId = pendingWindow.id,
            windowConfig = profileStub.placeholderWindowConfig,
            profileId = profileId,
            profileVersion = profileVersion,
        )

        if (openedWindow == null) {
            // Overlap policy returned null → skip this cycle
            log.warn { "WindowOpenJob: open blocked by overlap policy for profile $profileId" }
            return
        }

        log.info { "Window opened: id=${openedWindow.id}, profileId=$profileId" }

        // ── Step 4: Schedule one-shot close for duration-based windows ───────
        // TODO Phase 0: read real windowConfig.closeTrigger from profile
        // Example: if closeTrigger is TimeBased with windowDuration (not closeCron),
        //          schedule the one-shot close job.
        //
        // val closeTrigger = profile.windowConfig.closeTrigger
        // if (closeTrigger is WindowTrigger.TimeBased && closeTrigger.closeCron == null) {
        //     val closeAt = timeTriggerEvaluator.computeCloseTime(closeTrigger, openedWindow.openedAt!!)
        //     if (closeAt != null) {
        //         windowSchedulingService.scheduleOneshotClose(profile, openedWindow.id, closeAt)
        //     }
        // }

        // ── Step 5: Create PENDING window for the next cycle ─────────────────
        // This keeps the "next window" always visible in the UI after a window opens.
        // TODO Phase 0: use real profile object
        // windowSchedulingService.createNextPendingWindow(profile)

        // ── Step 6: Fire ON_OPEN action chain ────────────────────────────────
        // TODO Phase 1d: wire WorkflowOrchestrator
        // profile.actionsFor(ActionCondition.ON_OPEN).forEach { action ->
        //     workflowOrchestrator.execute(action, openedWindow)
        // }

        log.info {
            "WindowOpenJob complete: windowId=${openedWindow.id}, profileId=$profileId. " +
                "Workflow orchestration wired in Phase 1d."
        }
    }
}

// ── Temporary stub ────────────────────────────────────────────────────────────
// Removed once Phase 0 connects the real ProfileRepository.

private data class ProfileStub(
    val profileId: String,
    val profileVersion: Int,
    val clientId: String,
) {
    val placeholderWindowConfig = com.transformplatform.common.domain.window.WindowConfig(
        openTrigger = WindowTrigger.Manual(),
        closeTrigger = WindowTrigger.Manual(),
        overlapPolicy = com.transformplatform.common.domain.window.OverlapPolicy.SKIP_NEW,
    )
}
