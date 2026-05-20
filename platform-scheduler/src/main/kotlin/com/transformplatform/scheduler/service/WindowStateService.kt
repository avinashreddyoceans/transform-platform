package com.transformplatform.scheduler.service

import com.transformplatform.common.domain.window.OverlapPolicy
import com.transformplatform.common.domain.window.WindowConfig
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.domain.window.WindowStatus
import com.transformplatform.common.event.WindowClosedEvent
import com.transformplatform.common.event.WindowClosingEvent
import com.transformplatform.common.event.WindowForceClosedEvent
import com.transformplatform.common.event.WindowOpenedEvent
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import mu.KotlinLogging
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── WindowStateService ────────────────────────────────────────────────────────
//
// The state machine for WindowInstance.
//
// Valid transitions:
//   PENDING  →  OPEN          (WindowOpenJob fires the openTrigger)
//   OPEN     →  CLOSING       (WindowCloseJob: close trigger resolves)
//   OPEN     →  FORCE_CLOSED  (operator API or maxOpenDuration backstop)
//   CLOSING  →  CLOSED        (all ON_CLOSING actions completed)
//   CLOSING  →  ERROR         (ON_CLOSING action chain failed fatally)
//   any      →  ERROR         (unrecoverable failure)
//
// Overlap guard:
//   Before transitioning PENDING → OPEN, this service checks whether the profile
//   already has an OPEN or CLOSING window and applies the OverlapPolicy.
//   SKIP_NEW:              log a warning, return null (caller skips this cycle)
//   FORCE_CLOSE_EXISTING:  force-close the hanging window, then open the new one.

@Service
class WindowStateService(
    private val windowRepo: WindowInstanceRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {

    // ── PENDING → OPEN ────────────────────────────────────────────────────────

    /**
     * Transition a PENDING WindowInstance to OPEN.
     * Called by WindowOpenJob when the openTrigger fires.
     *
     * Returns the opened instance, or null if the overlap policy blocked the open.
     */
    fun open(windowInstanceId: String, windowConfig: WindowConfig, profileId: String, profileVersion: Int): WindowInstance? {
        // ── Overlap guard ─────────────────────────────────────────────────────
        val activeWindow = windowRepo.findActiveForProfile(profileId)
        if (activeWindow != null) {
            return when (windowConfig.overlapPolicy) {
                OverlapPolicy.SKIP_NEW -> {
                    log.warn {
                        "Profile $profileId already has an active window " +
                            "(id=${activeWindow.id}, status=${activeWindow.status}). " +
                            "Skipping new open per SKIP_NEW overlap policy."
                    }
                    null
                }
                OverlapPolicy.FORCE_CLOSE_EXISTING -> {
                    log.warn {
                        "Profile $profileId already has an active window " +
                            "(id=${activeWindow.id}). Force-closing per FORCE_CLOSE_EXISTING policy."
                    }
                    forceClose(
                        windowInstanceId = activeWindow.id,
                        reason = "Force-closed by overlap policy: new window cycle started",
                    )
                    // Proceed to open the new window below
                    performOpen(windowInstanceId, profileId, profileVersion)
                }
            }
        }

        return performOpen(windowInstanceId, profileId, profileVersion)
    }

    private fun performOpen(windowInstanceId: String, profileId: String, profileVersion: Int): WindowInstance {
        val now = Instant.now()
        val opened = windowRepo.updateStatus(
            id = windowInstanceId,
            newStatus = WindowStatus.OPEN,
            timestamp = now,
        )

        log.info { "Window OPENED: id=$windowInstanceId, profileId=$profileId" }

        eventPublisher.publishEvent(
            WindowOpenedEvent(
                windowInstanceId = windowInstanceId,
                profileId = profileId,
                clientId = "", // enriched by caller with profile.clientId
                profileVersion = profileVersion,
                openedAt = now,
            ),
        )

        return opened
    }

    // ── OPEN → CLOSING ────────────────────────────────────────────────────────

    /**
     * Transition an OPEN window to CLOSING.
     * Called by WindowCloseJob when the close trigger fully resolves.
     */
    fun startClosing(windowInstanceId: String): WindowInstance {
        val window = requireWindow(windowInstanceId, WindowStatus.OPEN)

        val closing = windowRepo.updateStatus(
            id = windowInstanceId,
            newStatus = WindowStatus.CLOSING,
        )

        log.info {
            "Window CLOSING: id=$windowInstanceId, eventCount=${window.eventCount}"
        }

        eventPublisher.publishEvent(
            WindowClosingEvent(
                windowInstanceId = windowInstanceId,
                profileId = window.profileId,
                clientId = "",
                eventCount = window.eventCount,
            ),
        )

        return closing
    }

    // ── CLOSING → CLOSED ──────────────────────────────────────────────────────

    /**
     * Transition CLOSING → CLOSED.
     * Called by WorkflowOrchestrator after all ON_CLOSING actions complete.
     */
    fun close(windowInstanceId: String, totalRecordsProcessed: Int = 0): WindowInstance {
        val window = requireWindow(windowInstanceId, WindowStatus.CLOSING)
        val now = Instant.now()

        val closed = windowRepo.updateStatus(
            id = windowInstanceId,
            newStatus = WindowStatus.CLOSED,
            timestamp = now,
        )

        log.info {
            "Window CLOSED: id=$windowInstanceId, " +
                "eventCount=${window.eventCount}, records=$totalRecordsProcessed, " +
                "duration=${durationDesc(window.openedAt, now)}"
        }

        eventPublisher.publishEvent(
            WindowClosedEvent(
                windowInstanceId = windowInstanceId,
                profileId = window.profileId,
                clientId = "",
                finalStatus = WindowStatus.CLOSED,
                eventCount = window.eventCount,
                totalRecordsProcessed = totalRecordsProcessed,
                openedAt = window.openedAt,
                closedAt = now,
            ),
        )

        return closed
    }

    // ── → FORCE_CLOSED ────────────────────────────────────────────────────────

    /**
     * Force-close a window from any non-terminal status.
     * Used by:
     *   - Operator via POST /api/windows/{id}/close
     *   - maxOpenDuration backstop (safety timer)
     *   - OverlapPolicy.FORCE_CLOSE_EXISTING
     */
    fun forceClose(windowInstanceId: String, reason: String): WindowInstance {
        val window = windowRepo.findById(windowInstanceId)
            ?: error("WindowInstance $windowInstanceId not found")

        if (window.isTerminal) {
            log.warn { "Window $windowInstanceId is already terminal (${window.status}) — skipping force-close" }
            return window
        }

        val now = Instant.now()
        val forceClosed = windowRepo.updateStatus(
            id = windowInstanceId,
            newStatus = WindowStatus.FORCE_CLOSED,
            reason = reason,
            timestamp = now,
        )

        log.warn { "Window FORCE_CLOSED: id=$windowInstanceId, reason=$reason" }

        eventPublisher.publishEvent(
            WindowForceClosedEvent(
                windowInstanceId = windowInstanceId,
                profileId = window.profileId,
                reason = reason,
            ),
        )

        return forceClosed
    }

    // ── → ERROR ───────────────────────────────────────────────────────────────

    /**
     * Mark a window as ERROR.
     * Called when a workflow step fails fatally and ON_ERROR chain also fails,
     * or when an unrecoverable infrastructure error occurs.
     */
    fun markError(windowInstanceId: String, reason: String): WindowInstance {
        val updated = windowRepo.updateStatus(
            id = windowInstanceId,
            newStatus = WindowStatus.ERROR,
            reason = reason,
        )
        log.error { "Window ERROR: id=$windowInstanceId, reason=$reason" }
        return updated
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun requireWindow(id: String, expectedStatus: WindowStatus): WindowInstance {
        val window = windowRepo.findById(id) ?: error("WindowInstance $id not found")
        check(window.status == expectedStatus) {
            "Expected window $id to be $expectedStatus but was ${window.status}"
        }
        return window
    }

    private fun durationDesc(openedAt: Instant?, closedAt: Instant): String {
        if (openedAt == null) return "unknown"
        val ms = closedAt.toEpochMilli() - openedAt.toEpochMilli()
        return when {
            ms < 1_000 -> "${ms}ms"
            ms < 60_000 -> "${ms / 1_000}s"
            ms < 3_600_000 -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
            else -> "${ms / 3_600_000}h ${(ms % 3_600_000) / 60_000}m"
        }
    }
}
