package com.transformplatform.api.controller.profile

import com.transformplatform.api.service.profile.ProfileService
import com.transformplatform.common.domain.profile.Profile
import com.transformplatform.common.domain.profile.ProfileStatus
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── ProfileController ─────────────────────────────────────────────────────────
//
// REST API for Profile CRUD + lifecycle operations.
//
// Routes:
//   GET    /api/profiles             — list all (optional ?clientId=&status= filters)
//   GET    /api/profiles/{id}        — get one (full detail with windowConfig + actions)
//   POST   /api/profiles             — create (returns 201 + Location header)
//   PUT    /api/profiles/{id}        — update (full replace of config)
//   DELETE /api/profiles/{id}        — soft-delete
//   POST   /api/profiles/{id}/enable  — DRAFT|DISABLED → ENABLED
//   POST   /api/profiles/{id}/disable — ENABLED → DISABLED
//   POST   /api/profiles/{id}/trigger — manually trigger a window open

@RestController
@RequestMapping("/api/profiles")
class ProfileController(
    private val profileService: ProfileService,
) {

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping
    fun listProfiles(
        @RequestParam(required = false) clientId: String?,
        @RequestParam(required = false) status: String?,
    ): List<ProfileSummaryResponse> {
        val statusFilter = status?.let {
            runCatching { ProfileStatus.valueOf(it.uppercase()) }.getOrNull()
        }
        return profileService.findAll(clientId = clientId, status = statusFilter)
            .map { it.toSummary() }
    }

    // ── Get ───────────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    fun getProfile(@PathVariable id: String): ProfileResponse = profileService.findById(id).toResponse()

    // ── Create ────────────────────────────────────────────────────────────────

    @PostMapping
    fun createProfile(@RequestBody request: CreateProfileRequest): ResponseEntity<ProfileResponse> {
        val profile = Profile(
            name = request.name,
            clientId = request.clientId,
            description = request.description,
            windowConfig = request.windowConfig,
            actions = request.actions,
            tags = request.tags,
            createdBy = request.createdBy,
            updatedBy = request.createdBy,
        )
        val created = profileService.create(profile)
        log.info { "Profile created via API: id=${created.id}, name='${created.name}'" }
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .header("Location", "/api/profiles/${created.id}")
            .body(created.toResponse())
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @PutMapping("/{id}")
    fun updateProfile(@PathVariable id: String, @RequestBody request: UpdateProfileRequest): ProfileResponse {
        val updated = Profile(
            id = id,
            name = request.name,
            clientId = request.clientId,
            description = request.description ?: "",
            windowConfig = request.windowConfig,
            actions = request.actions,
            tags = request.tags ?: emptyMap(),
            updatedBy = request.updatedBy,
            // createdBy / createdAt are preserved by ProfileService.update()
            createdBy = "system",
        )
        return profileService.update(id, updated).toResponse()
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteProfile(@PathVariable id: String) {
        profileService.delete(id)
        log.info { "Profile soft-deleted via API: id=$id" }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @PostMapping("/{id}/enable")
    fun enableProfile(@PathVariable id: String): ProfileResponse {
        val (profile, pendingWindow) = profileService.enable(id)
        log.info {
            "Profile enabled via API: id=$id. " +
                (if (pendingWindow != null) "Pending window: ${pendingWindow.id}" else "Manual trigger.")
        }
        return profile.toResponse()
    }

    @PostMapping("/{id}/disable")
    fun disableProfile(@PathVariable id: String): ProfileResponse = profileService.disable(id).toResponse()

    @PostMapping("/{id}/trigger")
    fun triggerNow(@PathVariable id: String): Map<String, Any?> {
        val window = profileService.triggerNow(id)
        return mapOf(
            "profileId" to id,
            "windowId" to window?.id,
            "status" to "triggered",
            "triggeredAt" to Instant.now().toString(),
        )
    }

    // ── Validate ──────────────────────────────────────────────────────────────
    //
    // Validate a profile config payload without persisting it.
    // Used by the UI config editor and CI pre-validation pipelines.
    // Returns 200 with empty errors list (valid) or 422 with error details (invalid).

    @PostMapping("/{id}/validate")
    fun validateProfile(@PathVariable id: String, @RequestBody request: UpdateProfileRequest): ResponseEntity<ProfileValidationResponse> {
        val profile = profileService.findById(id)

        val candidate = profile.copy(
            name = request.name,
            clientId = request.clientId,
            description = request.description ?: profile.description,
            windowConfig = request.windowConfig,
            actions = request.actions,
            tags = request.tags ?: profile.tags,
        )

        val errors = profileService.validate(candidate)

        return if (errors.isEmpty()) {
            ResponseEntity.ok(ProfileValidationResponse(valid = true, errors = emptyList()))
        } else {
            ResponseEntity.unprocessableEntity().body(
                ProfileValidationResponse(valid = false, errors = errors),
            )
        }
    }

    // Error handling is provided globally by GlobalExceptionHandler (@RestControllerAdvice).
}
