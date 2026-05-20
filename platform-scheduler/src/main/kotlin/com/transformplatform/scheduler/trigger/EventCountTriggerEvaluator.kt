package com.transformplatform.scheduler.trigger

import com.transformplatform.common.domain.trigger.WindowTrigger
import org.springframework.stereotype.Component

// ── EventCountTriggerEvaluator ────────────────────────────────────────────────
//
// Handles WindowTrigger.EventCount.
//
// Flink analogy: CountTrigger.
//
// rearmable = false (default):
//   Fires FIRE_AND_PURGE when eventCount reaches threshold.
//   Closes the window. Equivalent to Flink's CountTrigger with FIRE_AND_PURGE.
//
// rearmable = true:
//   Fires FIRE when threshold is crossed, keeps the window alive.
//   Re-arms: fires again every `threshold` additional events.
//   Equivalent to Flink's CountTrigger returning FIRE.
//   Use case: "process in micro-batches of 500 events, but keep window open
//   until 17:00" — pair with COMPOUND_AND(EVENT_COUNT(500, rearmable), TIME_BASED).

@Component
class EventCountTriggerEvaluator : TriggerEvaluator<WindowTrigger.EventCount> {

    override fun supports(trigger: com.transformplatform.common.domain.trigger.WindowTrigger): Boolean = trigger is WindowTrigger.EventCount

    /**
     * Called on every event ingestion. Checks if the count threshold is met.
     * The eventCount on the window is the count AFTER this event was added.
     */
    override fun evaluateOnEvent(trigger: WindowTrigger.EventCount, context: EvaluationContext): TriggerEvaluationResult {
        val count = context.window.eventCount

        val thresholdCrossed = if (trigger.rearmable) {
            // Re-armable: fire every N events (100, 200, 300, ...)
            count > 0 && count % trigger.threshold == 0
        } else {
            // One-shot: fire exactly when count reaches threshold
            count >= trigger.threshold
        }

        if (!thresholdCrossed) {
            return TriggerEvaluationResult.continueOpen(
                reason = "EVENT_COUNT: $count / ${trigger.threshold} events accumulated",
            )
        }

        return if (trigger.rearmable) {
            TriggerEvaluationResult.fire(
                reason = "EVENT_COUNT: threshold ${trigger.threshold} reached at count $count " +
                    "(rearmable — window stays open)",
            )
        } else {
            TriggerEvaluationResult.fireAndPurge(
                reason = "EVENT_COUNT: threshold ${trigger.threshold} reached — closing window",
            )
        }
    }

    /**
     * Timer evaluation: check current count against threshold.
     * Handles the case where a timer fires to check a window that may have
     * accumulated enough events since the last evaluation.
     */
    override fun evaluateOnTimer(trigger: WindowTrigger.EventCount, context: EvaluationContext): TriggerEvaluationResult =
        evaluateOnEvent(trigger, context)
}
