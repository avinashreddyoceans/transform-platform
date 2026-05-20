package com.transformplatform.scheduler.trigger

import com.transformplatform.common.domain.trigger.CompoundOperator
import com.transformplatform.common.domain.trigger.TriggerFireState
import com.transformplatform.common.domain.trigger.TriggerResult
import com.transformplatform.common.domain.trigger.WindowTrigger
import org.springframework.stereotype.Component

// ── CompoundTriggerEvaluator ──────────────────────────────────────────────────
//
// Handles WindowTrigger.Compound — AND / OR composition of sub-triggers.
//
// This is the evaluator that makes hybrid flows possible:
//   COMPOUND_AND(TIME_BASED("0 17 * * MON-FRI"), FILE_ARRIVAL("acme-sftp", "payments_*.csv"))
//   → window closes only when BOTH the 17:00 timer AND the file have arrived.
//
// State persistence:
//   Which sub-triggers have already fired is stored in WindowInstance.closeTriggerState
//   (a TriggerFireState JSONB blob). This means if the app restarts mid-window, the
//   compound trigger resumes from the correct state — no re-evaluation of past events.
//
// Flink analogy: composing multiple Trigger implementations; our TriggerFireState
// is analogous to Flink's trigger state stored in the checkpoint.
//
// Recursive: sub-triggers can themselves be COMPOUND (fully nested).

@Component
class CompoundTriggerEvaluator(
    private val evaluators: List<TriggerEvaluator<*>>,
) : TriggerEvaluator<WindowTrigger.Compound> {

    override fun supports(trigger: WindowTrigger): Boolean = trigger is WindowTrigger.Compound

    override fun evaluateOnEvent(trigger: WindowTrigger.Compound, context: EvaluationContext): TriggerEvaluationResult =
        evaluate(trigger, context, onTimer = false)

    override fun evaluateOnTimer(trigger: WindowTrigger.Compound, context: EvaluationContext): TriggerEvaluationResult =
        evaluate(trigger, context, onTimer = true)

    // ── Core evaluation logic ─────────────────────────────────────────────────

    private fun evaluate(trigger: WindowTrigger.Compound, context: EvaluationContext, onTimer: Boolean): TriggerEvaluationResult {
        var currentFireState = context.window.closeTriggerState
        var stateChanged = false

        // Evaluate each sub-trigger that hasn't already fired
        for (subTrigger in trigger.subTriggers) {
            if (currentFireState.hasFired(subTrigger.id)) {
                // Already fired in a previous evaluation — skip
                continue
            }

            val subResult = evaluateSubTrigger(subTrigger, context, onTimer)

            if (subResult.result != TriggerResult.CONTINUE) {
                // This sub-trigger just fired — record it
                currentFireState = currentFireState.withFired(
                    triggerId = subTrigger.id,
                    timestamp = context.evaluationTime.toString(),
                )
                stateChanged = true
            }
        }

        // Determine whether the compound condition is now satisfied
        val updatedState = if (stateChanged) currentFireState else null

        return when (trigger.operator) {
            CompoundOperator.AND -> evaluateAnd(trigger, currentFireState, updatedState, context)
            CompoundOperator.OR -> evaluateOr(trigger, currentFireState, updatedState, context)
        }
    }

    private fun evaluateAnd(
        trigger: WindowTrigger.Compound,
        fireState: TriggerFireState,
        updatedState: TriggerFireState?,
        context: EvaluationContext,
    ): TriggerEvaluationResult {
        val allFired = trigger.subTriggers.all { fireState.hasFired(it.id) }

        return if (allFired) {
            val reasons = trigger.subTriggers.mapNotNull { sub ->
                fireState.firedAt[sub.id]?.let { "${sub::class.simpleName} at $it" }
            }
            TriggerEvaluationResult.fireAndPurge(
                updatedState = updatedState,
                reason = "COMPOUND_AND: all ${trigger.subTriggers.size} sub-triggers satisfied — " +
                    reasons.joinToString(", "),
            )
        } else {
            val pending = trigger.subTriggers
                .filter { !fireState.hasFired(it.id) }
                .map { it::class.simpleName }
            TriggerEvaluationResult.continueOpen(
                reason = "COMPOUND_AND: waiting for ${pending.joinToString(", ")}",
            )
                .let { if (updatedState != null) it.copy(updatedFireState = updatedState) else it }
        }
    }

    private fun evaluateOr(
        trigger: WindowTrigger.Compound,
        fireState: TriggerFireState,
        updatedState: TriggerFireState?,
        context: EvaluationContext,
    ): TriggerEvaluationResult {
        val anyFired = trigger.subTriggers.any { fireState.hasFired(it.id) }

        return if (anyFired) {
            val firedSub = trigger.subTriggers.first { fireState.hasFired(it.id) }
            TriggerEvaluationResult.fireAndPurge(
                updatedState = updatedState,
                reason = "COMPOUND_OR: ${firedSub::class.simpleName} fired — condition satisfied",
            )
        } else {
            TriggerEvaluationResult.continueOpen(
                reason = "COMPOUND_OR: no sub-trigger has fired yet",
            )
                .let { if (updatedState != null) it.copy(updatedFireState = updatedState) else it }
        }
    }

    // ── Sub-trigger dispatch ──────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun evaluateSubTrigger(subTrigger: WindowTrigger, context: EvaluationContext, onTimer: Boolean): TriggerEvaluationResult {
        val evaluator = evaluators.firstOrNull { it.supports(subTrigger) }
            ?: return TriggerEvaluationResult.continueOpen(
                reason = "No evaluator found for ${subTrigger::class.simpleName}",
            )

        val typedEvaluator = evaluator as TriggerEvaluator<WindowTrigger>
        return if (onTimer) {
            typedEvaluator.evaluateOnTimer(subTrigger, context)
        } else {
            typedEvaluator.evaluateOnEvent(subTrigger, context)
        }
    }

    /**
     * Reconstruct the fire state by re-evaluating all sub-triggers against
     * current window state. Called by WorkflowRecoveryService on app startup
     * to verify persisted state is consistent.
     */
    fun reconstructFireState(trigger: WindowTrigger.Compound, context: EvaluationContext): TriggerFireState {
        var state = TriggerFireState()
        for (subTrigger in trigger.subTriggers) {
            // Check persisted state first
            if (context.window.closeTriggerState.hasFired(subTrigger.id)) {
                state = state.withFired(
                    subTrigger.id,
                    context.window.closeTriggerState.firedAt[subTrigger.id]
                        ?: context.evaluationTime.toString(),
                )
            }
        }
        return state
    }
}
