package com.transformplatform.api.controller.workflow

import com.transformplatform.common.domain.workflow.WorkflowStatus
import com.transformplatform.scheduler.repository.WorkflowExecutionRepository
import mu.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val log = KotlinLogging.logger {}

// ── WorkflowExecutionController ───────────────────────────────────────────────
//
// Read-only REST endpoints for workflow execution history.
//
// Endpoints:
//   GET /api/executions                      — list all executions (filterable)
//   GET /api/executions/{id}                 — single execution detail + steps
//   GET /api/windows/{id}/executions         — executions for a window
//   GET /api/profiles/{profileId}/executions — executions for a profile

@RestController
@RequestMapping("/api")
class WorkflowExecutionController(
    private val execRepo: WorkflowExecutionRepository,
) {

    // ── GET /api/executions ────────────────────────────────────────────────────

    @GetMapping("/executions")
    fun listExecutions(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false, defaultValue = "100") limit: Int,
    ): List<WorkflowExecutionSummaryResponse> {
        val executions = if (status != null) {
            val wfStatus = try {
                WorkflowStatus.valueOf(status.uppercase())
            } catch (ex: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid execution status: $status")
            }
            execRepo.findByStatus(wfStatus)
        } else {
            execRepo.findAll(limit)
        }
        log.debug { "listExecutions: returning ${executions.size} executions (status=$status)" }
        return executions.map { it.toSummary() }
    }

    // ── GET /api/executions/{id} ───────────────────────────────────────────────

    @GetMapping("/executions/{id}")
    fun getExecution(@PathVariable id: String): ResponseEntity<WorkflowExecutionDetailResponse> {
        val execution = execRepo.findById(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(execution.toDetail())
    }

    // ── GET /api/windows/{id}/executions ──────────────────────────────────────

    @GetMapping("/windows/{windowId}/executions")
    fun getWindowExecutions(@PathVariable windowId: String): List<WorkflowExecutionSummaryResponse> =
        execRepo.findByWindowId(windowId).map { it.toSummary() }

    // ── GET /api/profiles/{profileId}/executions ──────────────────────────────

    @GetMapping("/profiles/{profileId}/executions")
    fun getProfileExecutions(
        @PathVariable profileId: String,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): List<WorkflowExecutionSummaryResponse> = execRepo.findByProfileId(profileId, limit).map { it.toSummary() }
}
