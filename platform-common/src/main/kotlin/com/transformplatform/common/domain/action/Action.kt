package com.transformplatform.common.domain.action

import com.fasterxml.jackson.annotation.JsonIgnore
import java.util.UUID

// ── ActionCondition ───────────────────────────────────────────────────────────
//
// When an action fires relative to the window lifecycle.
// Multiple actions can share the same condition; they run in executionOrder.

enum class ActionCondition {

    /** Fires immediately after the window opens. Use for: notifications, setup tasks. */
    ON_OPEN,

    /**
     * Fires when the close trigger resolves and the window has collected events.
     * This is the primary condition for data processing workflows.
     */
    ON_CLOSING,

    /**
     * Fires when the close trigger resolves but the window has zero events.
     * Use for: absence alerting, heartbeat notifications.
     * Only fires if WindowConfig.allowEmptyClose = true.
     */
    ON_EMPTY_CLOSE,

    /**
     * Fires on every `recurringInterval` tick while the window is open.
     * Use for: mid-window snapshots, partial deliveries, progress updates.
     * Analogous to Flink's PurgingTrigger returning FIRE (not FIRE_AND_PURGE).
     */
    RECURRING_WHILE_OPEN,

    /**
     * Fires when EventCount trigger threshold is reached.
     * If EventCount.rearmable = true, this fires repeatedly every N events.
     * Analogous to Flink's CountTrigger.
     */
    ON_THRESHOLD_REACHED,

    /**
     * Fires when any step in a previous action's workflow fails.
     * Use for: error notifications, compensating transactions, alerting.
     * The error context (step name, error message) is available in the StepContext.
     */
    ON_ERROR,

    /**
     * Fires when a file arrives on a watched integration (FileArrival trigger).
     * Can trigger immediately on file arrival rather than waiting for window close.
     * Use for: real-time file processing as files land throughout the day.
     */
    ON_FILE_ARRIVED,

    /**
     * Flink-inspired: fires when an event arrives after the window has closed
     * and within the allowedLateness grace period (lateEventBehaviour = REFIRE_ACTION_CHAIN).
     * Use for: late data reconciliation, correction workflows, audit logging of late arrivals.
     */
    ON_LATE_EVENT,
}

// ── Action ────────────────────────────────────────────────────────────────────
//
// An action is a named, ordered, condition-triggered workflow.
// It owns a sequence of WorkflowSteps that the orchestrator executes in order.
//
// Actions are stored as a JSONB array inside ProfileEntity — they are always
// queried and updated together with the profile, avoiding N+1 queries.

data class Action(

    val id: String = UUID.randomUUID().toString(),

    /** Human-readable name shown in logs and the UI. */
    val name: String,

    /** The lifecycle condition that triggers this action. */
    val condition: ActionCondition,

    /**
     * Sort order among actions with the same [condition].
     * Allows multiple ON_CLOSING actions to run in a defined sequence.
     * Use gaps (10, 20, 30) to leave room for insertions.
     */
    val executionOrder: Int = 10,

    /**
     * If false: when any step fails, the entire workflow is marked FAILED and
     * execution stops. [ON_ERROR] action chain fires.
     *
     * If true: step failures are logged and recorded, but the next step runs.
     * The workflow is marked COMPLETED_WITH_ERRORS.
     *
     * Default: false. Most financial workflows should fail fast.
     */
    val continueOnFailure: Boolean = false,

    /**
     * The ordered list of steps that form this action's workflow.
     * The orchestrator executes these in ascending [WorkflowStep.executionOrder].
     * Only enabled steps are executed.
     */
    val steps: List<WorkflowStep> = emptyList(),

    /** When false, this action is skipped entirely. Useful for temporarily disabling. */
    val enabled: Boolean = true,
) {

    /**
     * Returns only the steps that will actually execute, in ascending [WorkflowStep.executionOrder].
     *
     * @JsonIgnore — this is a computed view of [steps], not a stored field.  Without this,
     * Jackson serialises it into the JSONB string, and on read-back uses "getter-as-setter"
     * mode to populate the collection by calling [add] on the result of the getter.  But
     * Kotlin's [sortedBy] returns Arrays.asList(…) which is fixed-size, so [add] throws
     * [UnsupportedOperationException] wrapped in a [com.fasterxml.jackson.databind.JsonMappingException].
     */
    @get:JsonIgnore
    val enabledSteps: List<WorkflowStep>
        get() = steps.filter { it.enabled }.sortedBy { it.executionOrder }

    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("Action name must not be blank")
        if (steps.isEmpty()) add("Action '$name' has no steps — it will do nothing")
        val duplicateOrders = steps.groupBy { it.executionOrder }
            .filter { it.value.size > 1 }
            .keys
        if (duplicateOrders.isNotEmpty()) {
            add("Action '$name' has duplicate step executionOrders: $duplicateOrders")
        }
    }
}
