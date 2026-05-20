package com.transformplatform.scheduler.service

import com.transformplatform.common.domain.trigger.TriggerResult
import com.transformplatform.common.domain.trigger.WindowTrigger
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.scheduler.repository.ProfileRepository
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import com.transformplatform.scheduler.trigger.EvaluationContext
import com.transformplatform.scheduler.trigger.TriggerEvaluator
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── WindowCloseChecker ────────────────────────────────────────────────────────
//
// Closes windows that are due.  Called by WindowOrchestratorJob every tick.
//
// Two passes per tick:
//
// Pass 1 — timed close:
//   SELECT * FROM windows
//   WHERE status = 'OPEN'
//     AND scheduled_close_at IS NOT NULL
//     AND scheduled_close_at <= NOW()
//   FOR UPDATE SKIP LOCKED
//   → evaluate the close trigger; close if resolved
//
// Pass 2 — session-gap expiry:
//   SELECT * FROM windows WHERE status = 'OPEN'
//   (filtered to SESSION_GAP trigger type in Phase 1a)
//   → check (now - window.updatedAt) >= inactivityGap
//
// Not handled here (event-driven closes):
//   FILE_ARRIVAL and EVENT_COUNT close triggers are evaluated by
//   FileArrivalService / EventIngestionService at event arrival time.
//   They call windowStateService.startClosing() directly.

@Service
class WindowCloseChecker(
    private val windowRepo: WindowInstanceRepository,
    private val profileRepo: ProfileRepository,
    private val windowStateService: WindowStateService,
    private val triggerEvaluators: List<TriggerEvaluator<*>>,
    /**
     * Optional: injected by platform-api (WorkflowOrchestratorImpl).
     * When present, runs ON_CLOSING action chains before closing the window.
     * When absent (e.g. during testing), windows close immediately.
     */
    private val workflowOrchestrator: WorkflowOrchestrator? = null,
) {

    fun checkAndClose(): Int {
        val now = Instant.now()
        var closed = 0
        closed += processTimedCloses(now)
        closed += processSessionGapCloses(now)
        return closed
    }

    // ── Pass 1: timed close ───────────────────────────────────────────────────

    private fun processTimedCloses(now: Instant): Int {
        val dueWindows = windowRepo.findDueToClose(now)
        if (dueWindows.isEmpty()) return 0

        log.info { "WindowCloseChecker: ${dueWindows.size} window(s) due to close" }

        var closed = 0
        for (window in dueWindows) {
            try {
                val profile = profileRepo.findById(window.profileId)
                if (profile == null) {
                    log.error {
                        "WindowCloseChecker: profile ${window.profileId} not found " +
                            "for window ${window.id} — skipping"
                    }
                    continue
                }

                val didClose = evaluateAndClose(window, profile.windowConfig.closeTrigger, now)
                if (didClose) closed++
            } catch (ex: Exception) {
                log.error(ex) {
                    "WindowCloseChecker: error closing window ${window.id} — skipping"
                }
            }
        }
        return closed
    }

    // ── Pass 2: session-gap close ─────────────────────────────────────────────

    private fun processSessionGapCloses(now: Instant): Int {
        // Phase 1a: add trigger_type column to windows table so this query is
        // WHERE status='OPEN' AND trigger_type='SESSION_GAP' — no profile join needed.
        // For now: load all OPEN windows and filter by profile config.
        val openWindows = windowRepo.findOpenWithSessionGapTrigger()
        var closed = 0

        for (window in openWindows) {
            val profile = profileRepo.findById(window.profileId) ?: continue
            val closeTrigger = profile.windowConfig.closeTrigger
            if (closeTrigger !is WindowTrigger.SessionGap) continue

            try {
                val evalContext = EvaluationContext(
                    window = window,
                    windowConfig = profile.windowConfig,
                    evaluationTime = now,
                )

                @Suppress("UNCHECKED_CAST")
                val evaluator = triggerEvaluators.firstOrNull { it.supports(closeTrigger) }
                    as? TriggerEvaluator<WindowTrigger>
                    ?: continue

                val result = evaluator.evaluateOnTimer(closeTrigger, evalContext)

                when (result.result) {
                    TriggerResult.FIRE, TriggerResult.FIRE_AND_PURGE -> {
                        log.info {
                            "WindowCloseChecker: session gap expired — window ${window.id}, " +
                                "profileId=${window.profileId}"
                        }
                        executeCloseSequence(window.id)
                        closed++
                    }
                    TriggerResult.PURGE -> {
                        windowStateService.startClosing(window.id)
                        windowStateService.close(window.id)
                        closed++
                    }
                    TriggerResult.CONTINUE -> Unit
                }
            } catch (ex: Exception) {
                log.error(ex) {
                    "WindowCloseChecker: error evaluating session gap for window ${window.id}"
                }
            }
        }
        return closed
    }

    // ── Trigger evaluation ────────────────────────────────────────────────────

    private fun evaluateAndClose(window: WindowInstance, closeTrigger: WindowTrigger, now: Instant): Boolean {
        val profile = profileRepo.findById(window.profileId) ?: return false

        val evalContext = EvaluationContext(
            window = window,
            windowConfig = profile.windowConfig,
            evaluationTime = now,
        )

        @Suppress("UNCHECKED_CAST")
        val evaluator = triggerEvaluators.firstOrNull { it.supports(closeTrigger) }
            as? TriggerEvaluator<WindowTrigger>

        if (evaluator == null) {
            log.error {
                "WindowCloseChecker: no evaluator for ${closeTrigger::class.simpleName} " +
                    "on window ${window.id}"
            }
            return false
        }

        val evalResult = evaluator.evaluateOnTimer(closeTrigger, evalContext)

        evalResult.updatedFireState?.let { windowRepo.updateCloseTriggerState(window.id, it) }

        return when (evalResult.result) {
            TriggerResult.CONTINUE -> false
            TriggerResult.FIRE, TriggerResult.FIRE_AND_PURGE -> {
                log.info {
                    "WindowCloseChecker: close trigger fired — window ${window.id}. " +
                        "Reason: ${evalResult.reason}"
                }
                executeCloseSequence(window.id)
                true
            }
            TriggerResult.PURGE -> {
                log.info { "WindowCloseChecker: PURGE empty window ${window.id}" }
                windowStateService.startClosing(window.id)
                windowStateService.close(window.id)
                true
            }
        }
    }

    /**
     * Run OPEN → CLOSING → (ON_CLOSING actions) → CLOSED.
     *
     * Phase 1d: WorkflowOrchestrator runs the ON_CLOSING action chain synchronously.
     * The orchestrator is injected from platform-api (WorkflowOrchestratorImpl).
     * If absent, windows close immediately (backwards-compatible for tests).
     */
    private fun executeCloseSequence(windowInstanceId: String) {
        windowStateService.startClosing(windowInstanceId)

        val totalRecords = if (workflowOrchestrator != null) {
            try {
                workflowOrchestrator.executeClosingActions(windowInstanceId)
            } catch (ex: Exception) {
                log.error(ex) {
                    "WindowCloseChecker: ON_CLOSING workflow failed for window $windowInstanceId"
                }
                windowStateService.markError(
                    windowInstanceId,
                    "ON_CLOSING workflow failed: ${ex.message}",
                )
                return
            }
        } else {
            0
        }

        windowStateService.close(windowInstanceId, totalRecords)
    }
}
