package com.transformplatform.scheduler.trigger

import com.transformplatform.common.domain.trigger.TriggerFireState
import com.transformplatform.common.domain.trigger.TriggerResult
import com.transformplatform.common.domain.trigger.WindowTrigger
import com.transformplatform.common.domain.window.WindowConfig
import com.transformplatform.common.domain.window.WindowInstance
import java.time.Instant

// ── EvaluationContext ─────────────────────────────────────────────────────────
//
// All the runtime information a TriggerEvaluator needs to make its decision.
// Passed to every evaluate() call.

data class EvaluationContext(
    /** The window being evaluated. Contains current eventCount and trigger state. */
    val window: WindowInstance,

    /** Full profile config — contains windowConfig.timeZone etc. */
    val windowConfig: WindowConfig,

    /**
     * The instant at which evaluation is happening.
     * Normally Instant.now() — injected for testability.
     */
    val evaluationTime: Instant = Instant.now(),

    /**
     * For EVENT_TIME semantics: the timestamp of the event that just arrived.
     * Null when evaluating on a timer (not triggered by an event).
     */
    val eventTimestamp: Instant? = null,
)

// ── TriggerEvaluator ──────────────────────────────────────────────────────────
//
// Flink-inspired: each trigger type has a dedicated evaluator.
// Evaluators are Spring @Component beans — the CompoundTriggerEvaluator
// discovers and delegates to them automatically.
//
// Corresponds to Flink's Trigger<T, W> interface, specifically:
//   onElement()        → evaluateOnEvent()
//   onProcessingTime() → evaluateOnTimer()
//   onEventTime()      → evaluateOnTimer() (with EVENT_TIME context)

interface TriggerEvaluator<T : WindowTrigger> {

    /** Returns true if this evaluator handles the given trigger type. */
    fun supports(trigger: WindowTrigger): Boolean

    /**
     * Called when a new event is ingested into the window.
     * Use for: EVENT_COUNT threshold checks, FILE_ARRIVAL detection,
     * SESSION_GAP timer reset.
     *
     * Flink analogy: Trigger.onElement()
     */
    fun evaluateOnEvent(trigger: T, context: EvaluationContext): TriggerEvaluationResult

    /**
     * Called when a scheduled timer fires (Quartz job or scheduled task).
     * Use for: TIME_BASED cron evaluation, SESSION_GAP inactivity check,
     * maxOpenDuration backstop.
     *
     * Flink analogy: Trigger.onProcessingTime() / Trigger.onEventTime()
     */
    fun evaluateOnTimer(trigger: T, context: EvaluationContext): TriggerEvaluationResult
}

// ── TriggerEvaluationResult ───────────────────────────────────────────────────
//
// The output of an evaluation. Wraps TriggerResult with additional state so
// the window engine knows whether to persist updated trigger state.

data class TriggerEvaluationResult(

    /**
     * The action the window engine should take.
     * See TriggerResult for full semantics.
     */
    val result: TriggerResult,

    /**
     * Updated trigger state to persist.
     * Null if no state change occurred (avoids unnecessary DB writes).
     * For compound triggers: records which sub-triggers have now fired.
     */
    val updatedFireState: TriggerFireState? = null,

    /**
     * Human-readable description of why this result was produced.
     * Appears in window/trigger audit logs.
     */
    val reason: String = "",
) {
    companion object {
        fun continueOpen(reason: String = "") = TriggerEvaluationResult(TriggerResult.CONTINUE, reason = reason)

        fun fire(updatedState: TriggerFireState? = null, reason: String = "") =
            TriggerEvaluationResult(TriggerResult.FIRE, updatedState, reason)

        fun fireAndPurge(updatedState: TriggerFireState? = null, reason: String = "") =
            TriggerEvaluationResult(TriggerResult.FIRE_AND_PURGE, updatedState, reason)

        fun purge(reason: String = "") = TriggerEvaluationResult(TriggerResult.PURGE, reason = reason)
    }
}
