package com.transformplatform.scheduler.trigger

import com.transformplatform.common.domain.trigger.WindowTrigger
import com.transformplatform.scheduler.util.parseCron
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.ZonedDateTime

// ── TimeTriggerEvaluator ──────────────────────────────────────────────────────
//
// Handles WindowTrigger.TimeBased.
//
// Open evaluation:
//   Called by WindowOpenJob when the openCron fires.
//   Always returns FIRE_AND_PURGE (open the window, nothing to purge yet).
//
// Close evaluation:
//   Called by WindowCloseJob when the closeCron or derived windowDuration fires.
//   Returns FIRE_AND_PURGE to close the window and run the ON_CLOSING chain.
//
// Duration derivation:
//   If closeCron is null AND windowDuration is null:
//   The engine derives the duration from the interval between two consecutive
//   openCron firings. This ensures every TIME_BASED window is "full size".
//   e.g. "0 */3 * * *" fires at 00:00, 03:00, 06:00 → interval = PT3H.
//
// No-overlap guarantee:
//   The WindowOpenJob calls WindowStateService.hasOpenWindow(profileId) before
//   creating a new instance. If an open window exists, the new open is skipped
//   (or the existing window is force-closed, per OverlapPolicy).
//   This evaluator does not need to know about overlap — that guard lives in the job.

@Component
class TimeTriggerEvaluator : TriggerEvaluator<WindowTrigger.TimeBased> {

    override fun supports(trigger: WindowTrigger): Boolean = trigger is WindowTrigger.TimeBased

    /**
     * Called when a timer fires (WindowOpenJob or WindowCloseJob).
     * Time-based triggers are purely timer-driven; events don't affect them.
     */
    override fun evaluateOnTimer(trigger: WindowTrigger.TimeBased, context: EvaluationContext): TriggerEvaluationResult =
        TriggerEvaluationResult.fireAndPurge(
            reason = "TIME_BASED timer fired at ${context.evaluationTime}",
        )

    /**
     * Time-based triggers don't react to individual events.
     * Always CONTINUE — the timer job will fire when the cron fires.
     */
    override fun evaluateOnEvent(trigger: WindowTrigger.TimeBased, context: EvaluationContext): TriggerEvaluationResult =
        TriggerEvaluationResult.continueOpen(reason = "TIME_BASED: waiting for timer")

    // ── Duration derivation ───────────────────────────────────────────────────

    /**
     * Compute the effective window duration for a TIME_BASED trigger.
     *
     * Priority:
     *   1. closeCron is set → null returned (close is governed by a separate cron job)
     *   2. windowDuration is set → return it directly
     *   3. Neither → derive from interval between two consecutive openCron firings
     *
     * The derived duration is what the WindowCloseJob uses to schedule itself
     * after a window opens.
     */
    fun resolveWindowDuration(trigger: WindowTrigger.TimeBased): Duration? {
        if (trigger.closeCron != null) return null // separate cron governs close
        if (trigger.windowDuration != null) return trigger.windowDuration
        return deriveIntervalFromCron(trigger.openCron, trigger.timeZone.id)
    }

    /**
     * Derive the interval between two consecutive firings of a cron expression.
     * Works for uniform crons (every N minutes/hours). Returns null if the
     * cron is irregular (e.g. "0 9,17 * * *") and cannot be auto-derived.
     */
    fun deriveIntervalFromCron(cronExpression: String, timeZoneId: String): Duration? {
        return try {
            val zone = java.time.ZoneId.of(timeZoneId)
            val expr = parseCron(cronExpression) // accepts both 5-field Unix and 6-field Spring cron
            val now = ZonedDateTime.now(zone)
            val first = expr.next(now) ?: return null
            val second = expr.next(first) ?: return null
            val interval = Duration.between(first, second)
            // Only return the interval if it's a uniform step (sanity check)
            val third = expr.next(second)
            if (third != null) {
                val interval2 = Duration.between(second, third)
                if (interval == interval2) interval else null
            } else {
                interval
            }
        } catch (e: Exception) {
            null // unparseable or irregular cron — caller must supply windowDuration explicitly
        }
    }

    /**
     * Given an open trigger and a window open time, compute the exact instant
     * when the close job should fire.
     *
     * Returns null if the close is governed by a closeCron (separate job handles it).
     */
    fun computeCloseTime(trigger: WindowTrigger.TimeBased, openedAt: java.time.Instant): java.time.Instant? {
        val duration = resolveWindowDuration(trigger) ?: return null
        return openedAt.plus(duration)
    }

    /**
     * Validate that the trigger's cron expression(s) are parseable.
     * Called by Profile.validate().
     */
    fun validate(trigger: WindowTrigger.TimeBased): List<String> = buildList {
        try {
            parseCron(trigger.openCron) // accepts both 5-field Unix and 6-field Spring cron
        } catch (e: Exception) {
            add("openCron '${trigger.openCron}' is not a valid cron expression: ${e.message}")
        }
        if (trigger.closeCron != null) {
            val closeCron = trigger.closeCron!!
            try {
                parseCron(closeCron) // accepts both 5-field Unix and 6-field Spring cron
            } catch (e: Exception) {
                add("closeCron '$closeCron' is not a valid cron expression: ${e.message}")
            }
        }
        if (trigger.closeCron == null && trigger.windowDuration == null) {
            val derived = deriveIntervalFromCron(trigger.openCron, trigger.timeZone.id)
            if (derived == null) {
                add(
                    "openCron '${trigger.openCron}' has an irregular interval. " +
                        "Please set closeCron or windowDuration explicitly.",
                )
            }
        }
    }
}
