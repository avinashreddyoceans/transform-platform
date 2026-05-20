package com.transformplatform.scheduler.job

import com.transformplatform.common.domain.trigger.TriggerResult
import com.transformplatform.common.domain.trigger.WindowTrigger
import com.transformplatform.common.domain.window.WindowStatus
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import com.transformplatform.scheduler.service.JobDataKeys
import com.transformplatform.scheduler.service.WindowStateService
import com.transformplatform.scheduler.trigger.EvaluationContext
import com.transformplatform.scheduler.trigger.TriggerEvaluator
import mu.KotlinLogging
import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.PersistJobDataAfterExecution
import org.springframework.stereotype.Component
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── WindowCloseJob ────────────────────────────────────────────────────────────
//
// MANUAL / AD-HOC window close entry point.
//
// In the database-driven scheduling model, the primary close path is:
//   WindowOrchestratorJob → WindowCloseChecker → WindowStateService.startClosing()
//
// This job is retained for:
//   1. Emergency / manual override: force-close a specific window from the admin API.
//   2. Testing: trigger a close without waiting for the orchestrator tick.
//
// It is NOT registered as a CronTrigger by WindowSchedulingService anymore.
//
// Originally: Quartz job that fires when the closeTrigger's cron fires.
//
// This job does NOT unconditionally close the window. Instead it:
//   1. Finds the currently OPEN window for the profile
//   2. Evaluates the close trigger (which may be compound — not all conditions met yet)
//   3. If TriggerResult.FIRE_AND_PURGE → transitions OPEN → CLOSING → fires action chain
//   4. If TriggerResult.CONTINUE → persists updated compound trigger state and returns
//      (the next timer fire or event arrival will re-evaluate)
//
// This design means a COMPOUND_AND close trigger is evaluated every time ANY of its
// component conditions fire. The compound evaluator tracks which sub-triggers have
// fired in WindowInstance.closeTriggerState (JSONB) and only resolves when all are met.
//
// Example: closeTrigger = COMPOUND_AND(TIME_BASED("0 17 * * *"), FILE_ARRIVAL("acme-sftp", "*.csv"))
//   17:00 fires → WindowCloseJob runs → evaluates COMPOUND_AND
//     → TIME_BASED sub-trigger: FIRED (records in closeTriggerState)
//     → FILE_ARRIVAL sub-trigger: NOT YET FIRED (no matching file arrived)
//     → COMPOUND_AND result: CONTINUE — window stays OPEN
//   File arrives → FileArrivalService calls evaluateCloseCondition() directly
//     → FILE_ARRIVAL sub-trigger: FIRED
//     → COMPOUND_AND result: FIRE_AND_PURGE — window closes

@Component
@DisallowConcurrentExecution
@PersistJobDataAfterExecution
class WindowCloseJob(
    private val windowRepo: WindowInstanceRepository,
    private val windowStateService: WindowStateService,
    private val triggerEvaluators: List<TriggerEvaluator<*>>,
    // Phase 1d: private val workflowOrchestrator: WorkflowOrchestrator,
    // Phase 0:  private val profileRepository: ProfileRepository,
) : Job {

    override fun execute(context: JobExecutionContext) {
        val jobData = context.mergedJobDataMap
        val profileId = jobData.getString(JobDataKeys.PROFILE_ID)
        val windowInstanceId = jobData.getString(JobDataKeys.WINDOW_INSTANCE_ID)

        log.info {
            "WindowCloseJob fired: profileId=$profileId" +
                if (windowInstanceId != null) ", windowId=$windowInstanceId" else ""
        }

        // ── Find the active window ────────────────────────────────────────────
        val activeWindow = if (windowInstanceId != null) {
            // One-shot close: specific window targeted
            windowRepo.findById(windowInstanceId)
        } else {
            // Cron-based close: find whatever is OPEN for this profile
            windowRepo.findActiveForProfile(profileId)
        }

        if (activeWindow == null) {
            log.info { "WindowCloseJob: no active window found for profile $profileId — nothing to close" }
            return
        }

        if (activeWindow.status != WindowStatus.OPEN) {
            log.info {
                "WindowCloseJob: window ${activeWindow.id} is ${activeWindow.status} " +
                    "(not OPEN) — skipping"
            }
            return
        }

        // ── Evaluate the close trigger ────────────────────────────────────────
        // TODO Phase 0: load real windowConfig from profile
        // For now: stub a basic TimeBased close trigger so the logic compiles
        val closeTrigger = WindowTrigger.TimeBased(openCron = "0 17 * * *") // placeholder
        val windowConfig = activeWindow.let {
            com.transformplatform.common.domain.window.WindowConfig(
                openTrigger = WindowTrigger.Manual(),
                closeTrigger = closeTrigger,
            )
        }

        val evalContext = EvaluationContext(
            window = activeWindow,
            windowConfig = windowConfig,
            evaluationTime = Instant.now(),
        )

        @Suppress("UNCHECKED_CAST")
        val evaluator = triggerEvaluators.firstOrNull { it.supports(closeTrigger) }
            as? TriggerEvaluator<WindowTrigger>

        if (evaluator == null) {
            log.error { "No evaluator found for close trigger ${closeTrigger::class.simpleName} — cannot close window ${activeWindow.id}" }
            return
        }

        val evalResult = evaluator.evaluateOnTimer(closeTrigger, evalContext)
        log.info { "Close trigger eval: result=${evalResult.result}, reason=${evalResult.reason}" }

        // Persist updated compound trigger state if any sub-trigger fired
        evalResult.updatedFireState?.let { newState ->
            windowRepo.updateCloseTriggerState(activeWindow.id, newState)
        }

        when (evalResult.result) {
            TriggerResult.CONTINUE -> {
                log.info {
                    "WindowCloseJob: close conditions not yet fully met for window ${activeWindow.id}. " +
                        "Reason: ${evalResult.reason}"
                }
                // Will re-evaluate on the next timer fire or when FileArrivalService calls back
            }

            TriggerResult.FIRE, TriggerResult.FIRE_AND_PURGE -> {
                triggerClose(activeWindow.id, profileId)
            }

            TriggerResult.PURGE -> {
                // Close without running the action chain (empty window with allowEmptyClose=false)
                log.info { "WindowCloseJob: PURGE — closing empty window ${activeWindow.id} without running actions" }
                windowStateService.startClosing(activeWindow.id)
                windowStateService.close(activeWindow.id)
            }
        }
    }

    private fun triggerClose(windowInstanceId: String, profileId: String) {
        log.info { "Triggering close sequence for window $windowInstanceId" }

        // OPEN → CLOSING
        val closingWindow = windowStateService.startClosing(windowInstanceId)

        // TODO Phase 1d: fire ON_CLOSING action chain
        // profile.actionsFor(ActionCondition.ON_CLOSING).forEach { action ->
        //     workflowOrchestrator.execute(action, closingWindow)
        // }
        // The orchestrator calls windowStateService.close(windowInstanceId) when all actions complete.
        //
        // For now: immediately close (stub — no action chain yet)
        windowStateService.close(windowInstanceId)

        log.info { "Window $windowInstanceId closed. Action chain wired in Phase 1d." }
    }
}
