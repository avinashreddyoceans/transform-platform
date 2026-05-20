package com.transformplatform.api.service.workflow

import com.transformplatform.common.domain.filelog.FileLog
import com.transformplatform.common.domain.filelog.FileLogStatus
import com.transformplatform.common.domain.trigger.TriggerResult
import com.transformplatform.common.domain.trigger.WindowTrigger
import com.transformplatform.common.domain.window.FileHandle
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.domain.window.WindowStatus
import com.transformplatform.integration.storage.S3ArchivalService
import com.transformplatform.scheduler.repository.FileLogRepository
import com.transformplatform.scheduler.repository.ProfileRepository
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import com.transformplatform.scheduler.service.WindowStateService
import com.transformplatform.scheduler.service.WorkflowOrchestrator
import com.transformplatform.scheduler.trigger.EvaluationContext
import com.transformplatform.scheduler.trigger.FileArrivalTriggerEvaluator
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.InputStream
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── FileArrivalHandlerService ─────────────────────────────────────────────────
//
// Handles the full pipeline triggered when a new inbound file is recorded:
//
//   1. Upload file content to MinIO (S3ArchivalService) if a stream is provided
//   2. Build a FileHandle with the MinIO storage key
//   3. Add the file to matching OPEN window(s)
//   4. Evaluate the FILE_ARRIVAL close trigger
//   5. If the trigger fires:
//      a. Execute ON_FILE_ARRIVED action chain (WorkflowOrchestratorImpl)
//      b. Transition window: OPEN → CLOSING → CLOSED (via WindowStateService)
//   6. Update FileLog status to PROCESSED or FAILED
//
// Called by FileLogController.recordInboundFile() after saving the ledger entry.
// Also called by the submit-file test endpoint which provides actual file content.

@Service
@Transactional
class FileArrivalHandlerService(
    private val windowRepo: WindowInstanceRepository,
    private val profileRepo: ProfileRepository,
    private val fileLogRepo: FileLogRepository,
    private val windowStateService: WindowStateService,
    private val fileArrivalEvaluator: FileArrivalTriggerEvaluator,
    private val workflowOrchestrator: WorkflowOrchestrator,
    private val s3Service: S3ArchivalService,
) {

    /**
     * Called after an inbound file is recorded in the ledger.
     *
     * @param fileLog   The saved FileLog entry (provides integrationId, fileName, windowId, etc.)
     * @param content   Optional file content stream. When present, the file is uploaded to MinIO
     *                  and the storage key is used in the FileHandle. When null, the fileLog's
     *                  remoteIdentifier is used as the MinIO key (for SFTP flow where the file
     *                  is already in MinIO).
     */
    fun onFileArrived(fileLog: FileLog, content: InputStream? = null): FileArrivalResult {
        log.info {
            "FileArrivalHandler: processing file '${fileLog.fileName}' " +
                "(integrationId=${fileLog.integrationId}, windowId=${fileLog.windowId})"
        }

        // ── Step 1: determine the MinIO storage key ───────────────────────────

        val storageKey: String = if (content != null) {
            // Upload content to MinIO under the integration / date / filename path
            val integrationId = fileLog.integrationId ?: "manual"
            val result = s3Service.archive(
                integrationId = integrationId,
                fileName = fileLog.fileName,
                stream = content,
                contentType = fileLog.mimeType ?: "application/octet-stream",
            )
            log.info { "FileArrivalHandler: uploaded to MinIO key=${result.key}" }
            result.key
        } else {
            // SFTP/S3 flow: file already in MinIO, remoteIdentifier holds the key
            fileLog.remoteIdentifier ?: run {
                log.warn {
                    "FileArrivalHandler: no file content and no remoteIdentifier for " +
                        "'${fileLog.fileName}' — cannot process"
                }
                return FileArrivalResult(
                    matched = false,
                    windowId = null,
                    recordsProcessed = 0,
                    message = "No file content and no remoteIdentifier",
                )
            }
        }

        // ── Step 2: build the FileHandle ──────────────────────────────────────

        val fileHandle = FileHandle(
            integrationId = fileLog.integrationId ?: "manual",
            fileName = fileLog.fileName,
            remotePath = storageKey, // used by PARSE_FILE step to retrieve from MinIO
            fileSizeBytes = fileLog.fileSizeBytes,
            contentChecksum = fileLog.contentChecksum,
        )

        // ── Step 3: find the target OPEN window ───────────────────────────────

        // If the caller already knows the windowId, use it directly.
        // Otherwise, search for OPEN windows whose FILE_ARRIVAL close trigger matches.
        val targetWindow: WindowInstance? = when {
            fileLog.windowId != null -> windowRepo.findById(fileLog.windowId!!)
                ?.takeIf { it.status == WindowStatus.OPEN }
            fileLog.profileId != null -> windowRepo.findActiveForProfile(fileLog.profileId!!)
                ?.takeIf { it.status == WindowStatus.OPEN }
            else -> findMatchingOpenWindow(fileHandle)
        }

        if (targetWindow == null) {
            log.warn {
                "FileArrivalHandler: no matching OPEN window for '${fileLog.fileName}' " +
                    "(integration=${fileLog.integrationId}) — file logged but not processed"
            }
            return FileArrivalResult(
                matched = false,
                windowId = null,
                recordsProcessed = 0,
                message = "No matching OPEN window found for file '${fileLog.fileName}'",
            )
        }

        // ── Step 4: add the file handle to the window ─────────────────────────

        val updatedWindow = windowRepo.addArrivedFile(targetWindow.id, fileHandle)
        log.info {
            "FileArrivalHandler: added file '${fileLog.fileName}' to window ${targetWindow.id}"
        }

        // ── Step 5: evaluate FILE_ARRIVAL close trigger ───────────────────────

        val profile = profileRepo.findById(updatedWindow.profileId)
        if (profile == null) {
            log.error { "FileArrivalHandler: profile ${updatedWindow.profileId} not found" }
            return FileArrivalResult(
                matched = true,
                windowId = targetWindow.id,
                recordsProcessed = 0,
                message = "Profile not found for window ${targetWindow.id}",
            )
        }

        val closeTrigger = profile.windowConfig.closeTrigger

        val evalResult = fileArrivalEvaluator.evaluateOnEvent(
            trigger = closeTrigger as? WindowTrigger.FileArrival
                ?: return FileArrivalResult(
                    matched = true,
                    windowId = targetWindow.id,
                    recordsProcessed = 0,
                    message = "Window close trigger is not FILE_ARRIVAL — skipping workflow",
                ),
            context = EvaluationContext(
                window = updatedWindow,
                windowConfig = profile.windowConfig,
                evaluationTime = Instant.now(),
            ),
        )

        log.info {
            "FileArrivalHandler: trigger eval for window ${targetWindow.id}: " +
                "${evalResult.result} — ${evalResult.reason}"
        }

        // ── Step 6: run workflow + close window if trigger fired ───────────────

        return when (evalResult.result) {
            TriggerResult.FIRE, TriggerResult.FIRE_AND_PURGE -> {
                executeWorkflowAndClose(updatedWindow.id, fileLog.id)
            }
            TriggerResult.CONTINUE -> {
                // Update file log status to PROCESSING (awaiting more files / conditions)
                updateFileLogStatus(fileLog.id, FileLogStatus.RECEIVED)
                FileArrivalResult(
                    matched = true,
                    windowId = targetWindow.id,
                    recordsProcessed = 0,
                    message = "File recorded; close trigger not yet satisfied",
                )
            }
            TriggerResult.PURGE -> {
                // Empty close — transition to CLOSING → CLOSED without running actions
                runCatching { windowStateService.startClosing(updatedWindow.id) }
                runCatching { windowStateService.close(updatedWindow.id, 0) }
                updateFileLogStatus(fileLog.id, FileLogStatus.PROCESSED)
                FileArrivalResult(
                    matched = true,
                    windowId = targetWindow.id,
                    recordsProcessed = 0,
                    message = "Window closed (empty close — PURGE trigger)",
                )
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun executeWorkflowAndClose(windowId: String, fileLogId: String): FileArrivalResult {
        var totalRecords = 0

        try {
            // Run ON_FILE_ARRIVED actions (PARSE → VALIDATE → NOTIFY)
            totalRecords = workflowOrchestrator.executeOnFileArrived(windowId)
            updateFileLogStatus(fileLogId, FileLogStatus.PROCESSED)

            log.info {
                "FileArrivalHandler: ON_FILE_ARRIVED workflow done for window $windowId " +
                    "(records=$totalRecords)"
            }
        } catch (ex: Exception) {
            log.error(ex) {
                "FileArrivalHandler: ON_FILE_ARRIVED workflow FAILED for window $windowId"
            }
            updateFileLogStatus(fileLogId, FileLogStatus.FAILED, ex.message)
            // Don't close the window on workflow failure — leave in OPEN for retry
            return FileArrivalResult(
                matched = true,
                windowId = windowId,
                recordsProcessed = 0,
                message = "Workflow failed: ${ex.message}",
            )
        }

        // Transition window OPEN → CLOSING → CLOSED
        try {
            windowStateService.startClosing(windowId)
            windowStateService.close(windowId, totalRecords)
        } catch (ex: Exception) {
            log.error(ex) { "FileArrivalHandler: error transitioning window $windowId to CLOSED" }
        }

        return FileArrivalResult(
            matched = true,
            windowId = windowId,
            recordsProcessed = totalRecords,
            message = "Window closed successfully after processing $totalRecords record(s)",
        )
    }

    /**
     * Find the first OPEN window whose FILE_ARRIVAL close trigger matches the given file.
     * Used when no explicit windowId or profileId is provided.
     */
    private fun findMatchingOpenWindow(fileHandle: FileHandle): WindowInstance? {
        val openWindows = windowRepo.findByStatus(WindowStatus.OPEN)
        for (window in openWindows) {
            val profile = profileRepo.findById(window.profileId) ?: continue
            val closeTrigger = profile.windowConfig.closeTrigger as? WindowTrigger.FileArrival ?: continue

            val integrationMatches = closeTrigger.integrationId.isBlank() ||
                closeTrigger.integrationId == fileHandle.integrationId
            val patternMatches = matchesGlob(fileHandle.fileName, closeTrigger.filePattern)

            if (integrationMatches && patternMatches) {
                return window
            }
        }
        return null
    }

    private fun matchesGlob(name: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.matches(name)
    }

    private fun updateFileLogStatus(fileLogId: String, status: FileLogStatus, error: String? = null) {
        runCatching {
            val existing = fileLogRepo.findById(fileLogId) ?: return
            fileLogRepo.save(
                existing.copy(
                    status = status,
                    errorMessage = error,
                    processedAt = if (status == FileLogStatus.PROCESSED || status == FileLogStatus.FAILED) {
                        Instant.now()
                    } else {
                        existing.processedAt
                    },
                ),
            )
        }.onFailure { ex ->
            log.warn(ex) { "FileArrivalHandler: failed to update FileLog $fileLogId status to $status" }
        }
    }
}

// ── FileArrivalResult ─────────────────────────────────────────────────────────

data class FileArrivalResult(
    val matched: Boolean,
    val windowId: String?,
    val recordsProcessed: Int,
    val message: String,
)
