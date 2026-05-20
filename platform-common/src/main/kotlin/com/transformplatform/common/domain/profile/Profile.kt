package com.transformplatform.common.domain.profile

import com.transformplatform.common.domain.action.Action
import com.transformplatform.common.domain.action.ActionCondition
import com.transformplatform.common.domain.window.WindowConfig
import java.time.Instant
import java.util.UUID

// ── ProfileStatus ─────────────────────────────────────────────────────────────

enum class ProfileStatus {
    /** Newly created — not yet scheduled. Can be freely edited. */
    DRAFT,

    /**
     * Active — Quartz triggers are registered, windows will open on schedule.
     * Edits trigger a reschedule (atomic unschedule + reschedule).
     */
    ENABLED,

    /**
     * Paused — Quartz triggers are suspended.
     * Existing open windows continue to completion; no new windows open.
     */
    DISABLED,

    /** Soft-deleted — excluded from all queries by default. */
    DELETED,
}

// ── Profile ───────────────────────────────────────────────────────────────────
//
// The root aggregate. A Profile is the complete, immutable-at-runtime
// description of a client's data workflow.
//
// Stored as a single row in the `profiles` table with:
//   - Scalar columns for queryable fields (id, clientId, status, version)
//   - JSONB column for windowConfig (WindowConfig)
//   - JSONB array column for actions (List<Action>)
//
// This design keeps the aggregate together (no N+1) while remaining fully
// queryable in PostgreSQL via JSONB operators.

data class Profile(

    val id: String = UUID.randomUUID().toString(),

    /** Human-readable name. Must be unique per clientId. */
    val name: String,

    /** Client / tenant identifier. All windows and events are scoped to this. */
    val clientId: String,

    val description: String = "",

    val status: ProfileStatus = ProfileStatus.DRAFT,

    /**
     * Monotonically increasing version number.
     * Bumped on every PUT. Used for optimistic locking and revision history.
     */
    val version: Int = 1,

    /** The full window lifecycle configuration. */
    val windowConfig: WindowConfig,

    /**
     * The ordered action workflows for this profile.
     * Grouped by [ActionCondition] at runtime.
     */
    val actions: List<Action> = emptyList(),

    /** Free-form key-value tags for filtering and grouping. */
    val tags: Map<String, String> = emptyMap(),

    val createdBy: String,
    val createdAt: Instant = Instant.now(),
    val updatedBy: String,
    val updatedAt: Instant = Instant.now(),

) {
    // ── NO runtime scheduling fields here ─────────────────────────────────────
    //
    // Profile is a config aggregate. It is created once, versioned on every edit,
    // and otherwise left alone. Runtime state — when to open, when to close,
    // what data arrived — lives entirely in WindowInstance.
    //
    // Scheduling works like this:
    //   • When a profile is ENABLED, WindowSchedulingService creates a PENDING
    //     WindowInstance with scheduledOpenAt = next cron firing from now.
    //   • WindowOrchestratorJob polls:
    //       SELECT * FROM windows WHERE status=PENDING AND scheduled_open_at <= NOW()
    //     and opens each due window.
    //   • When a window opens, the engine computes scheduledCloseAt from the
    //     profile's close trigger config and writes it on the WindowInstance.
    //   • WindowOrchestratorJob polls:
    //       SELECT * FROM windows WHERE status=OPEN AND scheduled_close_at <= NOW()
    //     and closes each due window.
    //
    // The profile table never changes for scheduling reasons — only for config edits.

    /** Returns actions that match the given condition, sorted by executionOrder. */
    fun actionsFor(condition: ActionCondition): List<Action> = actions
        .filter { it.condition == condition && it.enabled }
        .sortedBy { it.executionOrder }

    /** Returns true if this profile will schedule windows automatically. */
    val isScheduled: Boolean
        get() = status == ProfileStatus.ENABLED

    /**
     * Full validation: profile fields + windowConfig + all actions + all steps.
     * Returns a flat list of all validation errors.
     * Empty = valid.
     */
    fun validate(): List<String> = buildList {
        if (name.isBlank()) add("Profile name must not be blank")
        if (clientId.isBlank()) add("Profile clientId must not be blank")

        addAll(windowConfig.validate().map { "windowConfig: $it" })

        actions.forEachIndexed { i, action ->
            addAll(action.validate().map { "actions[$i] '${action.name}': $it" })
            action.steps.forEachIndexed { j, step ->
                if (step.name.isBlank()) add("actions[$i].steps[$j]: step name must not be blank")
                if (step.retryPolicy.maxAttempts < 1) {
                    add("actions[$i].steps[$j] '${step.name}': maxAttempts must be >= 1")
                }
            }
        }
    }
}
