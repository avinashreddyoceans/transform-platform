package com.transformplatform.api.service.profile

import com.transformplatform.common.domain.profile.Profile
import com.transformplatform.common.domain.profile.ProfileStatus
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.event.ProfileDisabledEvent
import com.transformplatform.common.event.ProfileEnabledEvent
import com.transformplatform.common.event.ProfileUpdatedEvent
import com.transformplatform.scheduler.repository.ProfileRepository
import com.transformplatform.scheduler.service.WindowSchedulingService
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

private val log = KotlinLogging.logger {}

// ── ProfileService ────────────────────────────────────────────────────────────
//
// Manages the lifecycle of Profile objects: CRUD + enable/disable.
//
// The enable/disable path is the entry point to the entire window scheduling
// pipeline. When a profile is enabled, the WindowSchedulingService:
//   1. Computes nextOpenAt (next cron firing) and persists it on the Profile
//   2. Creates a PENDING WindowInstance for the next scheduled execution
//
// The WindowOrchestratorJob (single Quartz job, 30s tick) picks up profiles
// WHERE status=ENABLED AND next_open_at <= NOW() and opens windows as needed.
// No per-profile Quartz jobs are registered.
//
// Storage: ProfileRepository (in-memory stub for now; Phase 0 wires JPA).
//
// Validation: Profile.validate() runs before any create or update.
//             Enable fails fast if the profile config is invalid.

@Service
class ProfileService(
    private val profileRepo: ProfileRepository,
    private val windowSchedulingService: WindowSchedulingService,
    private val eventPublisher: ApplicationEventPublisher,
) {

    // ── CRUD ──────────────────────────────────────────────────────────────────

    /**
     * Create a new profile in DRAFT status.
     * Validates the profile config before persisting.
     *
     * @throws IllegalArgumentException if the config is invalid
     */
    fun create(profile: Profile): Profile {
        val errors = profile.validate()
        require(errors.isEmpty()) {
            "Profile validation failed:\n${errors.joinToString("\n") { "  - $it" }}"
        }

        val draft = profile.copy(
            id = UUID.randomUUID().toString(),
            status = ProfileStatus.DRAFT,
            version = 1,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

        profileRepo.save(draft)
        log.info { "Profile created: id=${draft.id}, name='${draft.name}', clientId=${draft.clientId}" }
        return draft
    }

    fun findById(id: String): Profile = profileRepo.findById(id) ?: throw NoSuchElementException("Profile $id not found")

    fun findAll(clientId: String? = null, status: ProfileStatus? = null): List<Profile> =
        profileRepo.findAll(clientId = clientId, status = status)
            .filter { clientId == null || it.clientId == clientId }
            .sortedByDescending { it.updatedAt }

    /**
     * Update a profile.
     * Bumps the version, persists the snapshot, and if the profile is ENABLED
     * recomputes nextOpenAt to reflect the new config.
     */
    fun update(id: String, updated: Profile): Profile {
        val existing = findById(id)

        check(existing.status != ProfileStatus.DELETED) { "Cannot update a deleted profile" }

        val errors = updated.validate()
        require(errors.isEmpty()) {
            "Profile update validation failed:\n${errors.joinToString("\n") { "  - $it" }}"
        }

        val saved = updated.copy(
            id = id,
            version = existing.version + 1,
            status = existing.status, // status changes via enable/disable, not update
            createdAt = existing.createdAt,
            createdBy = existing.createdBy,
            updatedAt = Instant.now(),
        )

        profileRepo.save(saved)

        // If profile is active, recompute nextOpenAt to pick up the new config
        if (saved.status == ProfileStatus.ENABLED) {
            log.info { "Profile $id updated while ENABLED — rescheduling" }
            windowSchedulingService.rescheduleProfile(saved)
        }

        eventPublisher.publishEvent(
            ProfileUpdatedEvent(
                profileId = id,
                clientId = saved.clientId,
                newVersion = saved.version,
                previousVersion = existing.version,
            ),
        )

        log.info { "Profile updated: id=$id, version=${saved.version}" }
        return saved
    }

    fun delete(id: String) {
        val existing = findById(id)

        // If it was enabled, unschedule first (clears nextOpenAt, cancels pending window)
        if (existing.status == ProfileStatus.ENABLED) {
            windowSchedulingService.unscheduleProfile(id)
        }

        profileRepo.save(existing.copy(status = ProfileStatus.DELETED, updatedAt = Instant.now()))
        log.info { "Profile soft-deleted: id=$id" }
    }

    // ── Activation flow ───────────────────────────────────────────────────────

    /**
     * Activate a profile: DRAFT or DISABLED → ENABLED.
     *
     * This is the entry point to the entire window scheduling pipeline.
     * After this call:
     *   - nextOpenAt is computed (next cron firing) and persisted on the Profile
     *   - A PENDING WindowInstance is created for the next scheduled execution
     *   - The WindowOrchestratorJob (30s tick) will pick up this profile and
     *     open its first window when nextOpenAt <= NOW()
     *
     * Flow:
     *   ┌──────────────────────────────────────────────────────────────────┐
     *   │  POST /api/profiles/{id}/enable                                  │
     *   │    → validate profile config                                     │
     *   │    → profile.status = ENABLED                                    │
     *   │    → WindowSchedulingService.scheduleProfile(profile)            │
     *   │        → compute nextOpenAt = next cron firing                   │
     *   │        → persist nextOpenAt on Profile (indexed column)          │
     *   │        → create PENDING WindowInstance                           │
     *   │    → publish ProfileEnabledEvent                                 │
     *   └──────────────────────────────────────────────────────────────────┘
     *
     * @return Pair of (updated profile, created PENDING window or null for MANUAL)
     * @throws IllegalStateException if the profile is already ENABLED or DELETED
     */
    fun enable(id: String): Pair<Profile, WindowInstance?> {
        val profile = findById(id)

        check(profile.status != ProfileStatus.ENABLED) {
            "Profile $id is already ENABLED"
        }
        check(profile.status != ProfileStatus.DELETED) {
            "Cannot enable a deleted profile"
        }

        // Validate config before activating — catch bad configs before they
        // cause runtime failures in the scheduler
        val errors = profile.validate()
        require(errors.isEmpty()) {
            "Cannot enable profile $id — config is invalid:\n" +
                errors.joinToString("\n") { "  - $it" }
        }

        val enabled = profile.copy(
            status = ProfileStatus.ENABLED,
            updatedAt = Instant.now(),
            updatedBy = "system", // TODO Phase 1b: replace with authenticated user from security context
        )
        profileRepo.save(enabled)

        log.info { "Enabling profile: id=$id, name='${enabled.name}'" }

        // Compute nextOpenAt + create first PENDING window
        val pendingWindow = windowSchedulingService.scheduleProfile(enabled)

        eventPublisher.publishEvent(
            ProfileEnabledEvent(
                profileId = id,
                clientId = enabled.clientId,
                profileVersion = enabled.version,
            ),
        )

        log.info {
            "Profile ENABLED: id=$id. " +
                if (pendingWindow != null) {
                    "Pending window created: ${pendingWindow.id}"
                } else {
                    "No pending window (MANUAL trigger)."
                }
        }

        return Pair(enabled, pendingWindow)
    }

    /**
     * Deactivate a profile: ENABLED → DISABLED.
     *
     * Clears nextOpenAt / nextCloseAt so the orchestrator stops selecting this profile.
     * Any in-flight windows (OPEN, CLOSING) continue to completion.
     * The PENDING window (if any) is cancelled.
     *
     * @throws IllegalStateException if the profile is not ENABLED
     */
    fun disable(id: String): Profile {
        val profile = findById(id)

        check(profile.status == ProfileStatus.ENABLED) {
            "Profile $id is not ENABLED (current status: ${profile.status})"
        }

        // Clear nextOpenAt/nextCloseAt + cancel pending window
        windowSchedulingService.unscheduleProfile(id)

        val disabled = profile.copy(
            status = ProfileStatus.DISABLED,
            updatedAt = Instant.now(),
            updatedBy = "system", // TODO Phase 1b: replace with authenticated user from security context
        )
        profileRepo.save(disabled)

        eventPublisher.publishEvent(
            ProfileDisabledEvent(
                profileId = id,
                clientId = disabled.clientId,
            ),
        )

        log.info { "Profile DISABLED: id=$id. In-flight windows will complete normally." }
        return disabled
    }

    /**
     * Validate a profile config without saving.
     * Used by POST /api/profiles/{id}/validate and by the UI config editor.
     *
     * @return list of validation error messages (empty = valid)
     */
    fun validate(profile: Profile): List<String> = profile.validate()

    /**
     * Manually trigger an immediate window open for a profile.
     * Works for both MANUAL trigger profiles and as an emergency override.
     *
     * @throws IllegalStateException if the profile is not ENABLED
     */
    fun triggerNow(id: String): WindowInstance? {
        val profile = findById(id)
        check(profile.status == ProfileStatus.ENABLED) {
            "Profile $id must be ENABLED to trigger manually (current: ${profile.status})"
        }
        log.info { "Manual trigger: creating immediate window for profile $id" }
        return windowSchedulingService.createNextPendingWindow(profile)
        // TODO Phase 1c: transition the created window to OPEN immediately
    }
}
