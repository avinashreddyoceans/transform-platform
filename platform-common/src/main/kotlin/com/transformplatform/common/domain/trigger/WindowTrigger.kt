package com.transformplatform.common.domain.trigger

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

// ── Flink-inspired TriggerResult ──────────────────────────────────────────────
//
// Borrowed directly from Flink's TriggerResult enum.
// Every TriggerEvaluator returns one of these values to tell the window engine
// what to do next.
//
//  CONTINUE      — keep the window open, keep collecting events
//  FIRE          — run the action chain but keep the window alive (e.g. recurring actions)
//  FIRE_AND_PURGE— run the action chain and close the window (normal close path)
//  PURGE         — discard all events and close the window without running actions
//                  (used when maxOpenDuration safety backstop fires with no events)

enum class TriggerResult {
    CONTINUE,
    FIRE,
    FIRE_AND_PURGE,
    PURGE,
}

// ── CompoundOperator ──────────────────────────────────────────────────────────

enum class CompoundOperator {
    /** All sub-triggers must have fired before this compound trigger resolves. */
    AND,

    /** Any single sub-trigger firing is enough to resolve this compound trigger. */
    OR,
}

// ── WindowTrigger sealed hierarchy ───────────────────────────────────────────
//
// Stored as JSONB in PostgreSQL (inside WindowConfig on ProfileEntity).
// Jackson @JsonTypeInfo / @JsonSubTypes annotations handle polymorphic
// serialisation / deserialisation transparently.
//
// Design principle: each subtype holds ONLY the fields it needs.
// No nullable "other type's fields" polluting the shape.
//
// Flink concepts implemented here:
//   ┌──────────────────────────────────────┬────────────────────────────────────┐
//   │ Flink concept                        │ Our equivalent                     │
//   ├──────────────────────────────────────┼────────────────────────────────────┤
//   │ EventTimeTrigger / ProcessingTime    │ TimeBased  (open + close cron)     │
//   │ CountTrigger                         │ EventCount (threshold + rearmable) │
//   │ Custom trigger: onElement()          │ FileArrival / EventCount           │
//   │ Session window inactivity gap        │ SessionGap                         │
//   │ Compound AND / OR                    │ Compound                           │
//   └──────────────────────────────────────┴────────────────────────────────────┘

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = WindowTrigger.TimeBased::class, name = "TIME_BASED"),
    JsonSubTypes.Type(value = WindowTrigger.FileArrival::class, name = "FILE_ARRIVAL"),
    JsonSubTypes.Type(value = WindowTrigger.EventCount::class, name = "EVENT_COUNT"),
    JsonSubTypes.Type(value = WindowTrigger.SessionGap::class, name = "SESSION_GAP"),
    JsonSubTypes.Type(value = WindowTrigger.Compound::class, name = "COMPOUND"),
    JsonSubTypes.Type(value = WindowTrigger.Manual::class, name = "MANUAL"),
)
sealed class WindowTrigger {

    abstract val id: String

    // ── TIME_BASED ────────────────────────────────────────────────────────────
    //
    // Window opens on `openCron` and closes either:
    //   1. on `closeCron`           — explicit schedule
    //   2. after `windowDuration`   — computed from open time
    //   3. after a derived duration — inferred from the cron interval
    //      e.g. "0 */3 * * *" (every 3 hours) → windowDuration = PT3H
    //
    // This ensures every TIME_BASED window is a "full-size" window even when no
    // explicit close is configured, and guarantees non-overlapping windows.
    //
    // Example configs:
    //   Open at 08:00 weekdays, close at 17:00 weekdays:
    //     openCron = "0 8 * * MON-FRI",  closeCron = "0 17 * * MON-FRI"
    //
    //   Open every 3 hours, run for the full 3-hour interval:
    //     openCron = "0 */3 * * *",  windowDuration = null  ← derived as PT3H
    //
    //   Open at midnight, run for exactly 6 hours (not until next cron):
    //     openCron = "0 0 * * *",  windowDuration = PT6H

    data class TimeBased(
        override val id: String = UUID.randomUUID().toString(),

        /** Cron expression for when the window opens (required). */
        val openCron: String,

        /**
         * Cron expression for when the window closes.
         * Takes priority over [windowDuration].
         * If null, [windowDuration] is used instead.
         */
        val closeCron: String? = null,

        /**
         * How long the window stays open from the moment it opens.
         * Used when [closeCron] is null.
         * If null, the engine derives the interval from [openCron].
         * e.g. "0 *\/3 * * *" → PT3H.
         */
        val windowDuration: Duration? = null,

        /** Time zone for evaluating the cron expressions. Defaults to UTC. */
        val timeZone: ZoneId = ZoneId.of("UTC"),
    ) : WindowTrigger()

    // ── FILE_ARRIVAL ──────────────────────────────────────────────────────────
    //
    // Fires when a file matching `filePattern` arrives on the given integration.
    // Used as a standalone trigger or as one sub-trigger inside a COMPOUND_AND
    // (e.g. "close when both 17:00 has passed AND the daily file has arrived").
    //
    // Flink analogy: custom trigger with onElement() checking file metadata.

    data class FileArrival(
        override val id: String = UUID.randomUUID().toString(),

        /** ID of the IntegrationEntity to watch (SFTP, S3, etc.). */
        val integrationId: String,

        /**
         * Glob pattern matched against the filename.
         * Examples: "payments_*.csv", "ACME_FEED_????????.txt"
         */
        val filePattern: String,

        /**
         * How often to poll the integration for new files.
         * Defaults to PT1M if null.
         */
        val pollInterval: Duration? = null,
    ) : WindowTrigger()

    // ── EVENT_COUNT ───────────────────────────────────────────────────────────
    //
    // Fires when the window's collected event count reaches `threshold`.
    // Flink analogy: CountTrigger.
    //
    // `rearmable = true` is equivalent to Flink's CountTrigger resetting after fire,
    // enabling micro-batching: fire every N events while the window stays open.

    data class EventCount(
        override val id: String = UUID.randomUUID().toString(),

        /** Number of events that must accumulate before the trigger fires. */
        val threshold: Int,

        /**
         * If true, the trigger re-arms after firing and fires again every `threshold` events.
         * Enables micro-batching within a long-running window.
         * Maps to Flink's CountTrigger with FIRE (not FIRE_AND_PURGE).
         */
        val rearmable: Boolean = false,
    ) : WindowTrigger()

    // ── SESSION_GAP ───────────────────────────────────────────────────────────
    //
    // Flink's session window: close the window after a gap of inactivity.
    // Useful when event bursts arrive unpredictably (e.g. a client uploads
    // a batch of events, then goes quiet). The window closes `inactivityGap`
    // after the last event arrived.
    //
    // `maxWindowDuration` is the safety backstop (Flink's allowedLateness equivalent
    // applied to the open side): even if events keep trickling in, force-close
    // the window after this duration.

    data class SessionGap(
        override val id: String = UUID.randomUUID().toString(),

        /**
         * Close the window when no events have arrived for this duration.
         * Flink analogy: EventTimeSessionWindows.withGap(Time.minutes(N)).
         */
        val inactivityGap: Duration,

        /**
         * Maximum duration the window can stay open regardless of activity.
         * Prevents a continuously active stream from never closing.
         */
        val maxWindowDuration: Duration? = null,
    ) : WindowTrigger()

    // ── COMPOUND ──────────────────────────────────────────────────────────────
    //
    // Composes multiple sub-triggers with AND or OR semantics.
    //
    // COMPOUND_AND: fires when ALL sub-triggers have individually fired.
    //   Use case: close when "17:00 has passed" AND "daily file has arrived".
    //   This is the hybrid flow — the window won't close until both conditions
    //   are satisfied, regardless of which fires first.
    //
    // COMPOUND_OR: fires when ANY sub-trigger fires.
    //   Use case: close when "500 events collected" OR "17:00 reached" — whichever comes first.
    //
    // Sub-triggers can themselves be COMPOUND — fully recursive.
    //
    // Flink analogy: composing multiple Trigger implementations.

    data class Compound(
        override val id: String = UUID.randomUUID().toString(),
        val operator: CompoundOperator,
        val subTriggers: List<WindowTrigger>,
    ) : WindowTrigger() {
        init {
            require(subTriggers.size >= 2) { "Compound trigger requires at least 2 sub-triggers" }
        }
    }

    // ── MANUAL ────────────────────────────────────────────────────────────────
    //
    // Window only opens or closes via an explicit API call.
    // POST /api/profiles/{id}/trigger  →  open
    // POST /api/windows/{id}/close     →  close
    //
    // Useful for ad-hoc or operator-driven workflows.

    data class Manual(
        override val id: String = UUID.randomUUID().toString(),
    ) : WindowTrigger()
}

// ── TriggerFireState ─────────────────────────────────────────────────────────
//
// Persisted as JSONB in WindowInstance.closeTriggerState.
// Tracks which sub-triggers have fired within a compound trigger, enabling
// crash recovery — if the app restarts mid-window, the state is restored
// from the DB and evaluation resumes correctly.

data class TriggerFireState(
    /** Maps trigger.id → true when that trigger has fired. */
    val fired: Map<String, Boolean> = emptyMap(),

    /** Maps trigger.id → ISO timestamp of when it fired. */
    val firedAt: Map<String, String> = emptyMap(),
) {
    fun hasFired(triggerId: String) = fired[triggerId] == true

    fun withFired(triggerId: String, timestamp: String) = copy(
        fired = fired + (triggerId to true),
        firedAt = firedAt + (triggerId to timestamp),
    )
}
