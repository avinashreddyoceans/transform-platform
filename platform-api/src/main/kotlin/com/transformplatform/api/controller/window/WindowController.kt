package com.transformplatform.api.controller.window

import com.transformplatform.common.domain.window.WindowDataRecordType
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.domain.window.WindowStatus
import com.transformplatform.scheduler.repository.ProfileRepository
import com.transformplatform.scheduler.repository.WindowDataRepository
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import com.transformplatform.scheduler.service.WindowStateService
import com.transformplatform.scheduler.service.WorkflowOrchestrator
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── WindowController ──────────────────────────────────────────────────────────
//
// REST endpoints for window instances and their collected data.
//
// Read endpoints:
//   GET /api/windows                      — list all windows (filterable)
//   GET /api/windows/{id}                 — single window detail
//   GET /api/windows/{id}/data            — data records collected in a window
//   GET /api/windows/{id}/data/{recordType} — filtered by record type
//   GET /api/windows/{id}/events          — alias for /data (paginated event list)
//   GET /api/profiles/{profileId}/windows — windows belonging to a profile
//
// Write endpoints:
//   POST /api/windows/{id}/open           — manually open a PENDING window
//   POST /api/windows/{id}/close          — force close any non-terminal window
//   POST /api/windows/{id}/reprocess      — re-run ON_CLOSING actions on a closed window

@RestController
@RequestMapping("/api")
class WindowController(
    private val windowRepo: WindowInstanceRepository,
    private val profileRepo: ProfileRepository,
    private val dataRepo: WindowDataRepository,
    private val windowStateService: WindowStateService,
    private val workflowOrchestrator: WorkflowOrchestrator,
) {

    // ── GET /api/windows ───────────────────────────────────────────────────────

    @GetMapping("/windows")
    fun listWindows(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) profileId: String?,
    ): List<WindowSummaryResponse> {
        val windows: List<WindowInstance> = if (profileId != null) {
            windowRepo.findByProfileId(profileId)
        } else if (status != null) {
            val windowStatus = try {
                WindowStatus.valueOf(status.uppercase())
            } catch (ex: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid window status: $status")
            }
            windowRepo.findByStatus(windowStatus)
        } else {
            windowRepo.findAll()
        }
        log.debug { "listWindows: returning ${windows.size} windows (status=$status, profileId=$profileId)" }
        return windows.map { it.toSummary() }
    }

    // ── GET /api/windows/{id} ─────────────────────────────────────────────────

    @GetMapping("/windows/{id}")
    fun getWindow(@PathVariable id: String): ResponseEntity<WindowDetailResponse> {
        val window = windowRepo.findById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(window.toDetail())
    }

    // ── GET /api/windows/{id}/data ────────────────────────────────────────────

    @GetMapping("/windows/{id}/data")
    fun getWindowData(
        @PathVariable id: String,
        @RequestParam(required = false) recordType: String?,
    ): ResponseEntity<List<WindowDataResponse>> {
        windowRepo.findById(id)
            ?: return ResponseEntity.notFound().build()

        val type = recordType?.let {
            try {
                WindowDataRecordType.valueOf(it.uppercase())
            } catch (ex: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid record type: $it")
            }
        }
        val records = dataRepo.findByWindow(id, type).map { it.toResponse() }
        return ResponseEntity.ok(records)
    }

    // ── GET /api/profiles/{profileId}/windows ─────────────────────────────────

    @GetMapping("/profiles/{profileId}/windows")
    fun getProfileWindows(@PathVariable profileId: String): List<WindowSummaryResponse> =
        windowRepo.findByProfileId(profileId).map { it.toSummary() }

    // ── POST /api/windows/{id}/open ───────────────────────────────────────────
    //
    // Manually transitions a PENDING window to OPEN.
    // Use this when a window has scheduledOpenAt=null (MANUAL trigger or cron
    // was fixed after initial creation) and you want to open it immediately.

    @PostMapping("/windows/{id}/open")
    fun openWindow(@PathVariable id: String): ResponseEntity<Map<String, Any?>> {
        val window = windowRepo.findById(id)
            ?: throw NoSuchElementException("Window $id not found")

        check(window.status == WindowStatus.PENDING) {
            "Window $id must be PENDING to open manually (current: ${window.status})"
        }

        val profile = profileRepo.findById(window.profileId)
            ?: throw NoSuchElementException("Profile ${window.profileId} not found")

        val opened = windowStateService.open(
            windowInstanceId = id,
            windowConfig = profile.windowConfig,
            profileId = window.profileId,
            profileVersion = window.profileVersion,
        ) ?: throw IllegalStateException(
            "Window $id could not be opened (overlap policy blocked it)",
        )

        log.info { "Window manually opened: id=$id, profileId=${window.profileId}" }

        return ResponseEntity.ok(
            mapOf(
                "windowId" to opened.id,
                "status" to opened.status.name,
                "openedAt" to opened.openedAt?.toString(),
                "profileId" to opened.profileId,
            ),
        )
    }

    // ── POST /api/windows/{id}/close ──────────────────────────────────────────
    //
    // Force-close a window from any non-terminal state.
    // Operator escape hatch — use when a window is stuck OPEN or CLOSING.
    // The reason field (optional, defaults to "Manually closed via API") is
    // stored in window.statusReason for audit purposes.

    @PostMapping("/windows/{id}/close")
    fun closeWindow(
        @PathVariable id: String,
        @RequestBody(required = false) body: Map<String, String>?,
    ): ResponseEntity<Map<String, Any?>> {
        val window = windowRepo.findById(id)
            ?: throw NoSuchElementException("Window $id not found")

        if (window.isTerminal) {
            throw IllegalStateException(
                "Window $id is already terminal (${window.status}) — cannot force close",
            )
        }

        val reason = body?.get("reason") ?: "Manually closed via API"
        val closed = windowStateService.forceClose(windowInstanceId = id, reason = reason)

        log.info { "Window force-closed: id=$id, reason=$reason, profileId=${window.profileId}" }

        return ResponseEntity.ok(
            mapOf(
                "windowId" to closed.id,
                "status" to closed.status.name,
                "statusReason" to closed.statusReason,
                "closedAt" to Instant.now().toString(),
                "profileId" to closed.profileId,
            ),
        )
    }

    // ── POST /api/windows/{id}/reprocess ──────────────────────────────────────
    //
    // Re-run the ON_CLOSING action chain on a CLOSED or FORCE_CLOSED window.
    // Creates new WorkflowExecution records (does not overwrite existing ones).
    // Useful when an action failed and manual retry is needed without reopening
    // a new window cycle.
    //
    // Returns the total records processed in the reprocessing run.

    @PostMapping("/windows/{id}/reprocess")
    fun reprocessWindow(@PathVariable id: String): ResponseEntity<Map<String, Any?>> {
        val window = windowRepo.findById(id)
            ?: throw NoSuchElementException("Window $id not found")

        check(window.status == WindowStatus.CLOSED || window.status == WindowStatus.FORCE_CLOSED) {
            "Window $id must be CLOSED or FORCE_CLOSED to reprocess (current: ${window.status})"
        }

        log.info { "Reprocessing window: id=$id, profileId=${window.profileId}" }

        val recordsProcessed = workflowOrchestrator.executeClosingActions(id)

        log.info {
            "Window reprocessed: id=$id, profileId=${window.profileId}, records=$recordsProcessed"
        }

        return ResponseEntity.ok(
            mapOf(
                "windowId" to id,
                "profileId" to window.profileId,
                "recordsProcessed" to recordsProcessed,
                "reprocessedAt" to Instant.now().toString(),
                "message" to "Reprocessing completed: $recordsProcessed record(s) processed",
            ),
        )
    }

    // ── GET /api/windows/{id}/events ──────────────────────────────────────────
    //
    // Paginated event list for a window.
    // Alias for /data — events and data records share the same table.
    // Supports optional ?recordType= filter and ?limit= paging.

    @GetMapping("/windows/{id}/events")
    fun getWindowEvents(
        @PathVariable id: String,
        @RequestParam(required = false) recordType: String?,
        @RequestParam(required = false, defaultValue = "200") limit: Int,
    ): ResponseEntity<WindowEventsResponse> {
        windowRepo.findById(id)
            ?: return ResponseEntity.notFound().build()

        val type = recordType?.let {
            try {
                WindowDataRecordType.valueOf(it.uppercase())
            } catch (ex: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid record type: $it")
            }
        }

        val records = dataRepo.findByWindow(id, type)
            .take(limit)
            .map { it.toResponse() }

        return ResponseEntity.ok(
            WindowEventsResponse(
                windowId = id,
                total = records.size,
                recordType = recordType,
                events = records,
            ),
        )
    }
}
