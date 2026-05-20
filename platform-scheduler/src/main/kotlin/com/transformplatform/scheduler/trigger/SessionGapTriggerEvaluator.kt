package com.transformplatform.scheduler.trigger

import com.transformplatform.common.domain.trigger.WindowTrigger
import org.springframework.stereotype.Component
import java.time.Duration

// ── SessionGapTriggerEvaluator ────────────────────────────────────────────────
//
// Handles WindowTrigger.SessionGap.
//
// Flink analogy: EventTimeSessionWindows.withGap() — closes the window after
// a period of inactivity, rather than at a fixed time.
//
// How it works:
//   - Every time an event arrives, the inactivity timer is reset.
//   - A Quartz job checks periodically (every inactivityGap / 2, minimum 30s).
//   - If (now - lastEventAt) >= inactivityGap → FIRE_AND_PURGE.
//   - If maxWindowDuration is set and (now - openedAt) >= maxWindowDuration → FIRE_AND_PURGE.
//
// The "last event time" is derived from the WindowInstance:
//   - updatedAt tracks the last modification (including event ingestion)
//   - The scheduler reads this to determine inactivity duration.
//
// Use case: "process a burst of events from a client; close the window 30 minutes
// after the last event; never let a window run longer than 4 hours."

@Component
class SessionGapTriggerEvaluator : TriggerEvaluator<WindowTrigger.SessionGap> {

    override fun supports(trigger: com.transformplatform.common.domain.trigger.WindowTrigger): Boolean = trigger is WindowTrigger.SessionGap

    /**
     * On every event: reset the inactivity clock.
     * Always returns CONTINUE — the gap check happens on the periodic timer.
     */
    override fun evaluateOnEvent(trigger: WindowTrigger.SessionGap, context: EvaluationContext): TriggerEvaluationResult =
        // The act of receiving an event resets the inactivity gap.
        // We just CONTINUE; the timer job will check the gap.
        TriggerEvaluationResult.continueOpen(
            reason = "SESSION_GAP: event received, inactivity clock reset",
        )

    /**
     * On timer: check if the inactivity gap or max duration has been exceeded.
     * Called by the periodic SessionGap check job (every inactivityGap / 2).
     */
    override fun evaluateOnTimer(trigger: WindowTrigger.SessionGap, context: EvaluationContext): TriggerEvaluationResult {
        val window = context.window
        val now = context.evaluationTime
        val openedAt = window.openedAt ?: return TriggerEvaluationResult.continueOpen()

        // Check maxWindowDuration backstop first
        if (trigger.maxWindowDuration != null) {
            val openDuration = Duration.between(openedAt, now)
            if (openDuration >= trigger.maxWindowDuration) {
                return TriggerEvaluationResult.fireAndPurge(
                    reason = "SESSION_GAP: maxWindowDuration ${trigger.maxWindowDuration} exceeded " +
                        "(window open for $openDuration)",
                )
            }
        }

        // Check inactivity gap
        val lastActivityAt = window.updatedAt
        val idleDuration = Duration.between(lastActivityAt, now)

        return if (idleDuration >= trigger.inactivityGap) {
            TriggerEvaluationResult.fireAndPurge(
                reason = "SESSION_GAP: inactivity gap ${trigger.inactivityGap} exceeded " +
                    "(last activity $idleDuration ago at $lastActivityAt)",
            )
        } else {
            val remaining = trigger.inactivityGap.minus(idleDuration)
            TriggerEvaluationResult.continueOpen(
                reason = "SESSION_GAP: active — $remaining remaining before gap closes window",
            )
        }
    }

    /**
     * How frequently the SessionGap check job should poll.
     * Set to inactivityGap / 2 to get reasonable precision without hammering the DB.
     * Minimum: 30 seconds.
     */
    fun checkInterval(trigger: WindowTrigger.SessionGap): Duration {
        val halfGap = trigger.inactivityGap.dividedBy(2)
        return if (halfGap < Duration.ofSeconds(30)) Duration.ofSeconds(30) else halfGap
    }
}
