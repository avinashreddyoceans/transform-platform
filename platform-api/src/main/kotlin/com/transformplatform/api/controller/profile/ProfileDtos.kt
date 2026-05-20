package com.transformplatform.api.controller.profile

import com.transformplatform.common.domain.action.Action
import com.transformplatform.common.domain.profile.Profile
import com.transformplatform.common.domain.trigger.WindowTrigger
import com.transformplatform.common.domain.window.WindowConfig
import java.time.Instant

// ── Request DTOs ──────────────────────────────────────────────────────────────

/**
 * Request body for POST /api/profiles (create) and PUT /api/profiles/{id} (update).
 *
 * windowConfig and actions are deserialized from JSON using the application
 * ObjectMapper — WindowTrigger's @JsonTypeInfo handles the polymorphic types.
 */
data class CreateProfileRequest(
    val name: String,
    val clientId: String,
    val description: String = "",
    val windowConfig: WindowConfig,
    val actions: List<Action> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
    val createdBy: String = "system",
)

data class UpdateProfileRequest(
    val name: String,
    val clientId: String,
    val description: String? = null,
    val windowConfig: WindowConfig,
    val actions: List<Action> = emptyList(),
    val tags: Map<String, String>? = null,
    val updatedBy: String = "system",
)

/**
 * Response for POST /api/profiles/{id}/validate.
 * Returns valid=true + empty errors list for a valid config,
 * or valid=false + non-empty errors list for an invalid config.
 */
data class ProfileValidationResponse(
    val valid: Boolean,
    val errors: List<String>,
)

// ── Response DTOs ─────────────────────────────────────────────────────────────

/**
 * Full profile response returned by GET /api/profiles/{id} and after create/update.
 *
 * Includes the full windowConfig and actions so the UI form can pre-populate
 * all fields without a second API call.
 *
 * openTriggerType / closeTriggerType are extracted from windowConfig for the
 * list table display (avoids the frontend parsing the nested trigger config).
 */
data class ProfileResponse(
    val id: String,
    val name: String,
    val clientId: String,
    val description: String,
    val status: String,
    val version: Int,
    val windowConfig: WindowConfig,
    val actions: List<Action>,
    val tags: Map<String, String>,
    /** Simple type name for UI display, e.g. "TIME_BASED", "FILE_ARRIVAL" */
    val openTriggerType: String,
    val closeTriggerType: String,
    val createdBy: String,
    val createdAt: Instant,
    val updatedBy: String,
    val updatedAt: Instant,
)

/**
 * Compact response used in list endpoints to avoid over-fetching.
 * Actions are replaced by their count (the list page doesn't render steps).
 */
data class ProfileSummaryResponse(
    val id: String,
    val name: String,
    val clientId: String,
    val description: String,
    val status: String,
    val version: Int,
    val openTriggerType: String,
    val closeTriggerType: String,
    val actionCount: Int,
    val tags: Map<String, String>,
    val updatedAt: Instant,
)

// ── Mapper helpers ─────────────────────────────────────────────────────────────

fun Profile.toResponse() = ProfileResponse(
    id = id,
    name = name,
    clientId = clientId,
    description = description,
    status = status.name,
    version = version,
    windowConfig = windowConfig,
    actions = actions,
    tags = tags,
    openTriggerType = windowConfig.openTrigger.triggerTypeName(),
    closeTriggerType = windowConfig.closeTrigger.triggerTypeName(),
    createdBy = createdBy,
    createdAt = createdAt,
    updatedBy = updatedBy,
    updatedAt = updatedAt,
)

fun Profile.toSummary() = ProfileSummaryResponse(
    id = id,
    name = name,
    clientId = clientId,
    description = description,
    status = status.name,
    version = version,
    openTriggerType = windowConfig.openTrigger.triggerTypeName(),
    closeTriggerType = windowConfig.closeTrigger.triggerTypeName(),
    actionCount = actions.size,
    tags = tags,
    updatedAt = updatedAt,
)

private fun WindowTrigger.triggerTypeName(): String = when (this) {
    is WindowTrigger.TimeBased -> "TIME_BASED"
    is WindowTrigger.FileArrival -> "FILE_ARRIVAL"
    is WindowTrigger.EventCount -> "EVENT_COUNT"
    is WindowTrigger.SessionGap -> "SESSION_GAP"
    is WindowTrigger.Compound -> "COMPOUND"
    is WindowTrigger.Manual -> "MANUAL"
}
