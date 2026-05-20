package com.transformplatform.scheduler.trigger

import com.transformplatform.common.domain.trigger.WindowTrigger
import com.transformplatform.common.domain.window.WindowInstance
import org.springframework.stereotype.Component

// ── FileArrivalTriggerEvaluator ───────────────────────────────────────────────
//
// Handles WindowTrigger.FileArrival.
//
// Evaluation strategy:
//   Unlike TIME_BASED, this trigger is event-driven — it fires the moment a
//   file matching the pattern arrives on the integration.
//
//   evaluateOnEvent() is called by FileArrivalService each time a file is
//   added to window.arrivedFileHandles. It checks whether the new file
//   matches the trigger's pattern and integration.
//
//   evaluateOnTimer() is a no-op for standalone FILE_ARRIVAL triggers
//   (it returns CONTINUE). However, within a COMPOUND_AND trigger, the timer
//   component (TIME_BASED) is handled by TimeTriggerEvaluator — this evaluator
//   only cares about the file part.
//
// Flink analogy: custom Trigger with onElement() checking file metadata.

@Component
class FileArrivalTriggerEvaluator : TriggerEvaluator<WindowTrigger.FileArrival> {

    override fun supports(trigger: com.transformplatform.common.domain.trigger.WindowTrigger): Boolean =
        trigger is WindowTrigger.FileArrival

    /**
     * Called when a new FileHandle is added to the window.
     * Returns FIRE_AND_PURGE if the file matches this trigger's pattern.
     */
    override fun evaluateOnEvent(trigger: WindowTrigger.FileArrival, context: EvaluationContext): TriggerEvaluationResult {
        val matchingFile = context.window.findFile(trigger.filePattern)

        return if (matchingFile != null &&
            (trigger.integrationId.isBlank() || matchingFile.integrationId == trigger.integrationId)
        ) {
            TriggerEvaluationResult.fireAndPurge(
                reason = "FILE_ARRIVAL: file '${matchingFile.fileName}' matched " +
                    "pattern '${trigger.filePattern}' on integration '${trigger.integrationId}'",
            )
        } else {
            TriggerEvaluationResult.continueOpen(
                reason = "FILE_ARRIVAL: no matching file yet for pattern '${trigger.filePattern}'",
            )
        }
    }

    /**
     * Timer-based evaluation: check if a file has already arrived.
     * Useful for compound trigger recovery after a crash — on restart we
     * re-evaluate all sub-triggers to reconstruct state.
     */
    override fun evaluateOnTimer(trigger: WindowTrigger.FileArrival, context: EvaluationContext): TriggerEvaluationResult =
        evaluateOnEvent(trigger, context)

    /**
     * Check whether the file arrival condition is already satisfied in the
     * current window state. Used by CompoundTriggerEvaluator during recovery.
     */
    fun isSatisfied(trigger: WindowTrigger.FileArrival, window: WindowInstance): Boolean {
        val matchingFile = window.findFile(trigger.filePattern)
        return matchingFile != null &&
            (trigger.integrationId.isBlank() || matchingFile.integrationId == trigger.integrationId)
    }
}
