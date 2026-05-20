package com.transformplatform.scheduler.service

import com.transformplatform.common.domain.trigger.WindowTrigger
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.domain.window.WindowStatus
import com.transformplatform.scheduler.repository.ProfileRepository
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import com.transformplatform.scheduler.trigger.TimeTriggerEvaluator
import com.transformplatform.scheduler.util.parseCron
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.ZonedDateTime

private val log = KotlinLogging.logger {}

// ── WindowOpenChecker ─────────────────────────────────────────────────────────
//
// Opens windows that are due.  Called by WindowOrchestratorJob every tick.
//
// Query:
//   SELECT * FROM windows
//   WHERE status = 'PENDING'
//     AND scheduled_open_at IS NOT NULL
//     AND scheduled_open_at <= NOW()
//   FOR UPDATE SKIP LOCKED            ← cluster-safe in Phase 1a
//
// For each due window:
//   1. Load its profile (for config: trigger, overlap policy, actions)
//   2. Run the overlap guard (WindowStateService — OverlapPolicy)
//   3. Transition PENDING → OPEN
//   4. Set scheduledCloseAt on the window (for time-based close triggers)
//   5. Create the NEXT PENDING window with the next cron firing as scheduledOpenAt

@Service
class WindowOpenChecker(
    private val windowRepo: WindowInstanceRepository,
    private val profileRepo: ProfileRepository,
    private val windowStateService: WindowStateService,
    private val timeTriggerEvaluator: TimeTriggerEvaluator,
) {

    /**
     * Find and open all windows whose scheduledOpenAt is now or in the past.
     * Returns the number of windows successfully opened.
     */
    fun checkAndOpen(): Int {
        val now = Instant.now()
        val dueWindows = windowRepo.findDueToOpen(now)

        if (dueWindows.isEmpty()) {
            log.debug { "WindowOpenChecker: no windows due to open at $now" }
            return 0
        }

        log.info { "WindowOpenChecker: ${dueWindows.size} window(s) due to open" }

        var opened = 0
        for (window in dueWindows) {
            try {
                val didOpen = processWindow(window, now)
                if (didOpen) opened++
            } catch (ex: Exception) {
                log.error(ex) {
                    "WindowOpenChecker: error processing window ${window.id} " +
                        "(profileId=${window.profileId}) — skipping this tick"
                }
                // Do not rethrow — one bad window must not block the others
            }
        }

        log.info { "WindowOpenChecker: opened $opened window(s) this tick" }
        return opened
    }

    private fun processWindow(window: WindowInstance, now: Instant): Boolean {
        // Load the profile config — needed for trigger config and overlap policy
        val profile = profileRepo.findById(window.profileId)

        if (profile == null) {
            log.error {
                "WindowOpenChecker: profile ${window.profileId} not found for window ${window.id}. " +
                    "Deleting orphaned PENDING window."
            }
            windowRepo.deleteById(window.id)
            return false
        }

        log.debug { "WindowOpenChecker: opening window ${window.id} for profile '${profile.name}'" }

        // ── PENDING → OPEN (overlap guard inside WindowStateService) ──────────
        val openedWindow = windowStateService.open(
            windowInstanceId = window.id,
            windowConfig = profile.windowConfig,
            profileId = profile.id,
            profileVersion = profile.version,
        )

        if (openedWindow == null) {
            // Blocked by OverlapPolicy.SKIP_NEW.
            // Reschedule this PENDING window to the next cron cycle so the
            // orchestrator doesn't keep re-selecting it every tick.
            val nextOpenAt = computeNextOpenAt(profile.windowConfig.openTrigger)
            if (nextOpenAt != null) {
                windowRepo.save(window.copy(scheduledOpenAt = nextOpenAt, updatedAt = Instant.now()))
                log.warn {
                    "WindowOpenChecker: open blocked for window ${window.id} by overlap policy. " +
                        "Rescheduled to $nextOpenAt."
                }
            } else {
                // Non-time-based trigger — delete the blocked PENDING window
                windowRepo.deleteById(window.id)
                log.warn {
                    "WindowOpenChecker: open blocked for window ${window.id} by overlap policy. " +
                        "No next firing time — deleted PENDING window."
                }
            }
            return false
        }

        val openedAt = openedWindow.openedAt ?: now

        log.info {
            "WindowOpenChecker: window opened — id=${openedWindow.id}, " +
                "profileId=${profile.id}, openedAt=$openedAt"
        }

        // ── Set scheduledCloseAt for time-based close triggers ─────────────────
        val scheduledCloseAt = computeScheduledCloseAt(profile.windowConfig.closeTrigger, openedAt)
        if (scheduledCloseAt != null) {
            windowRepo.setScheduledCloseAt(openedWindow.id, scheduledCloseAt)
            log.info {
                "WindowOpenChecker: scheduledCloseAt=$scheduledCloseAt set on window ${openedWindow.id}"
            }
        }

        // ── Create the NEXT PENDING window ─────────────────────────────────────
        val nextOpenAt = computeNextOpenAt(profile.windowConfig.openTrigger)
        if (nextOpenAt != null) {
            val nextWindow = WindowInstance(
                profileId = profile.id,
                profileVersion = profile.version,
                status = WindowStatus.PENDING,
                scheduledOpenAt = nextOpenAt,
            )
            windowRepo.save(nextWindow)
            log.info {
                "WindowOpenChecker: next PENDING window created — id=${nextWindow.id}, " +
                    "scheduledOpenAt=$nextOpenAt"
            }
        }

        return true
    }

    // ── Scheduling helpers ────────────────────────────────────────────────────

    /**
     * Compute the next time this profile's open trigger should fire.
     * Returns null for MANUAL / event-driven triggers.
     */
    private fun computeNextOpenAt(openTrigger: WindowTrigger): Instant? = when (openTrigger) {
        is WindowTrigger.TimeBased ->
            nextCronFiring(openTrigger.openCron, openTrigger.timeZone.id)
        is WindowTrigger.Compound ->
            // For compound open triggers, use the earliest time-based sub-trigger
            openTrigger.subTriggers
                .filterIsInstance<WindowTrigger.TimeBased>()
                .mapNotNull { nextCronFiring(it.openCron, it.timeZone.id) }
                .minOrNull()
                ?: Instant.now() // non-time compound triggers are ready immediately
        else -> null // MANUAL, FILE_ARRIVAL, EVENT_COUNT, SESSION_GAP — no fixed schedule
    }

    /**
     * Compute when the window should be scheduled to close, based on the close trigger.
     * Returns null for event-driven close triggers — those are evaluated per-event.
     */
    private fun computeScheduledCloseAt(closeTrigger: WindowTrigger, openedAt: Instant): Instant? = when (closeTrigger) {
        is WindowTrigger.TimeBased -> {
            if (closeTrigger.closeCron != null) {
                // Absolute: next closeCron firing after openedAt
                val closeCron = closeTrigger.closeCron!!
                nextCronFiringAfter(closeCron, closeTrigger.timeZone.id, openedAt)
            } else {
                // Duration-based: openedAt + resolved window duration
                timeTriggerEvaluator.computeCloseTime(closeTrigger, openedAt)
            }
        }
        // All other trigger types are event-driven — no fixed close time
        else -> null
    }

    private fun nextCronFiring(cronExpression: String, timeZoneId: String): Instant? =
        nextCronFiringAfter(cronExpression, timeZoneId, Instant.now())

    private fun nextCronFiringAfter(cronExpression: String, timeZoneId: String, after: Instant): Instant? {
        return try {
            val zone = java.time.ZoneId.of(timeZoneId)
            val expr = parseCron(cronExpression) // accepts both 5-field Unix and 6-field Spring cron
            expr.next(ZonedDateTime.ofInstant(after, zone))?.toInstant()
        } catch (e: Exception) {
            log.warn { "WindowOpenChecker: could not compute cron firing for '$cronExpression': ${e.message}" }
            null
        }
    }
}
