package com.transformplatform.common.domain.window

import com.transformplatform.common.domain.trigger.WindowTrigger
import java.time.Duration
import java.time.ZoneId

// ── TimeSemantics ─────────────────────────────────────────────────────────────
//
// Borrowed from Flink's time characteristics.
//
// PROCESSING_TIME: windows are evaluated against wall-clock time.
//   Simple, no watermarks needed. Used for most business batch workflows
//   where "now" is what matters — collect events today, close at 17:00 today.
//
// EVENT_TIME: windows are evaluated against a timestamp extracted from the event
//   payload. Critical for:
//     - Reprocessing historical data (events from yesterday land in yesterday's window)
//     - Handling delayed upstream systems (payment arrived 2 min late still lands in correct window)
//     - Backfill scenarios
//
// Default is PROCESSING_TIME — simpler, no config required.

enum class TimeSemantics {
    PROCESSING_TIME,
    EVENT_TIME,
}

// ── LateEventBehaviour ────────────────────────────────────────────────────────
//
// Flink routes late events (past allowedLateness) to a "side output" stream.
// We give the operator a choice of what to do with them.
//
// ACCEPT_INTO_NEXT_WINDOW  — silently route the event to the next open window instance.
//                            This is the most common production choice.
// REFIRE_ACTION_CHAIN      — keep the closed window alive for `allowedLateness` duration;
//                            late events cause the ON_LATE_EVENT action chain to fire.
//                            Analogous to Flink's FIRE (not FIRE_AND_PURGE) with allowed lateness.
// ROUTE_TO_DEAD_LETTER     — publish to a dead-letter topic / table for manual review.
//                            Use when missing an event is auditable (financial workflows).
// REJECT                   — drop silently. Only appropriate for non-critical streams.

enum class LateEventBehaviour {
    ACCEPT_INTO_NEXT_WINDOW,
    REFIRE_ACTION_CHAIN,
    ROUTE_TO_DEAD_LETTER,
    REJECT,
}

// ── OverlapPolicy ─────────────────────────────────────────────────────────────
//
// What happens when a WindowOpenJob fires but a window for this profile is already OPEN?
// We guarantee no overlapping windows per profile by default.
//
// SKIP_NEW       — log a warning and skip the new cycle. Safe default.
// FORCE_CLOSE_EXISTING — force-close the hanging window, then open the new one.
//                        Useful for self-healing when a window gets stuck.
// QUEUE          — Phase 5: queue the open request until current window closes.

enum class OverlapPolicy {
    SKIP_NEW,
    FORCE_CLOSE_EXISTING,
}

// ── WindowConfig ──────────────────────────────────────────────────────────────
//
// Full configuration block stored as JSONB on ProfileEntity.
// Drives the entire window lifecycle.

data class WindowConfig(

    // ── Triggers ───────────────────────────────────────────────────────────

    /**
     * When to open the window.
     * For TIME_BASED: cron expression.
     * For MANUAL: window only opens via POST /api/profiles/{id}/trigger.
     */
    val openTrigger: WindowTrigger,

    /**
     * When to close the window.
     *
     * For TIME_BASED: must specify closeCron or windowDuration.
     *   If closeCron is omitted AND windowDuration is omitted,
     *   the engine derives the duration from the openCron interval.
     *   Example: openCron = "0 *\/3 * * *" → windowDuration = PT3H
     *   This ensures every window is a "full-size" window.
     *
     * For COMPOUND: close only when the compound condition is fully satisfied.
     *   Example: AND(TIME_BASED(17:00), FILE_ARRIVAL(acme-sftp, "payments_*.csv"))
     *   → window won't close until BOTH conditions are met.
     *
     * For MANUAL: window only closes via POST /api/windows/{id}/close.
     */
    val closeTrigger: WindowTrigger,

    // ── Recurring ──────────────────────────────────────────────────────────

    /**
     * If set, fires RECURRING_WHILE_OPEN action chains on this interval
     * while the window is open. Does not close the window.
     * Analogous to Flink's PurgingTrigger with FIRE (not FIRE_AND_PURGE).
     */
    val recurringInterval: Duration? = null,

    // ── Time semantics (Flink-inspired) ────────────────────────────────────

    /**
     * Whether windows evaluate against event timestamps or system clock.
     * See [TimeSemantics] for full explanation.
     * Default: PROCESSING_TIME.
     */
    val timeSemantics: TimeSemantics = TimeSemantics.PROCESSING_TIME,

    /**
     * JSONPath or dot-notation field path to extract the event timestamp when
     * timeSemantics = EVENT_TIME. Example: "transaction.timestamp"
     * Must point to a field that can be parsed as ISO-8601 or epoch-millis.
     * Ignored when timeSemantics = PROCESSING_TIME.
     */
    val eventTimestampField: String? = null,

    // ── Late event handling (Flink-inspired) ───────────────────────────────

    /**
     * How long after the close trigger fires to still accept late-arriving events.
     * Analogous to Flink's allowedLateness().
     *
     * Example: allowedLateness = PT5M
     *   → if an event arrives up to 5 minutes after the window closes, it is
     *     handled according to [lateEventBehaviour] rather than dropped.
     *
     * Null means: no grace period — events arriving after close are immediately
     * subject to [lateEventBehaviour].
     */
    val allowedLateness: Duration? = null,

    /**
     * What to do with events that arrive after the window closes.
     * If [allowedLateness] is set, events within that grace period are handled here.
     * Default: ACCEPT_INTO_NEXT_WINDOW.
     */
    val lateEventBehaviour: LateEventBehaviour = LateEventBehaviour.ACCEPT_INTO_NEXT_WINDOW,

    // ── Safety backstops ───────────────────────────────────────────────────

    /**
     * Maximum duration a window can stay open regardless of other triggers.
     * Prevents stuck windows. If reached, fires FIRE_AND_PURGE.
     *
     * For TIME_BASED windows this defaults to (closeTrigger.windowDuration * 2)
     * if not set explicitly, giving headroom without being unbounded.
     */
    val maxOpenDuration: Duration? = null,

    /**
     * Whether to fire the ON_CLOSING action chain when a window closes with
     * zero collected events. Useful for audit/alerting.
     * Default: false (skip the chain on empty windows).
     */
    val allowEmptyClose: Boolean = false,

    // ── Deduplication ──────────────────────────────────────────────────────

    /** Whether to automatically deduplicate events on ingestion. Default: true. */
    val deduplicationEnabled: Boolean = true,

    // ── Overlap control ────────────────────────────────────────────────────

    /**
     * What happens if a new WindowOpenJob fires while a window for this profile
     * is still OPEN or CLOSING.
     * Default: SKIP_NEW (log warning, do not open a second window).
     */
    val overlapPolicy: OverlapPolicy = OverlapPolicy.SKIP_NEW,

    // ── Timezone ───────────────────────────────────────────────────────────

    /**
     * Time zone for evaluating all time-based expressions in this window.
     * Applies to TIME_BASED trigger cron evaluation and to event-time comparisons.
     * Individual triggers may override this.
     */
    val timeZone: ZoneId = ZoneId.of("UTC"),
) {

    /**
     * Validates that the config is internally consistent.
     * Called by ProfileService.validate() before persisting.
     */
    fun validate(): List<String> = buildList {
        // EVENT_TIME requires a field path
        if (timeSemantics == TimeSemantics.EVENT_TIME && eventTimestampField.isNullOrBlank()) {
            add("eventTimestampField must be set when timeSemantics = EVENT_TIME")
        }
        // REFIRE_ACTION_CHAIN late behaviour requires allowedLateness to be meaningful
        if (lateEventBehaviour == LateEventBehaviour.REFIRE_ACTION_CHAIN && allowedLateness == null) {
            add("allowedLateness must be set when lateEventBehaviour = REFIRE_ACTION_CHAIN")
        }
        // TimeBased open trigger sanity
        if (openTrigger is WindowTrigger.TimeBased) {
            if (openTrigger.openCron.isBlank()) {
                add("TimeBased openTrigger.openCron must not be blank")
            }
        }
        // Compound trigger must have at least 2 sub-triggers (enforced in constructor,
        // but surface it here for a clean validation error path too)
        if (closeTrigger is WindowTrigger.Compound) {
            if (closeTrigger.subTriggers.size < 2) {
                add("Compound closeTrigger must contain at least 2 sub-triggers")
            }
        }
    }
}
