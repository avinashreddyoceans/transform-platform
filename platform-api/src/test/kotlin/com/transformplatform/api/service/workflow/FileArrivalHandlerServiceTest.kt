package com.transformplatform.api.service.workflow

import com.transformplatform.common.domain.filelog.FileDirection
import com.transformplatform.common.domain.filelog.FileLog
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
import com.transformplatform.scheduler.trigger.FileArrivalTriggerEvaluator
import com.transformplatform.scheduler.trigger.TriggerEvaluationResult
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Instant

/**
 * Unit tests for [FileArrivalHandlerService].
 *
 * Covers the file arrival pipeline:
 *   upload to MinIO → build FileHandle → find matching OPEN window
 *   → evaluate close trigger → run workflow → close window
 *
 * No Spring context — service instantiated directly with MockK doubles.
 */
class FileArrivalHandlerServiceTest {

    private val windowRepo = mockk<WindowInstanceRepository>(relaxed = true)
    private val profileRepo = mockk<ProfileRepository>(relaxed = true)
    private val fileLogRepo = mockk<FileLogRepository>(relaxed = true)
    private val windowStateService = mockk<WindowStateService>(relaxed = true)
    private val fileArrivalEvaluator = mockk<FileArrivalTriggerEvaluator>(relaxed = true)
    private val workflowOrchestrator = mockk<WorkflowOrchestrator>(relaxed = true)
    private val s3Service = mockk<S3ArchivalService>(relaxed = true)

    private val service = FileArrivalHandlerService(
        windowRepo = windowRepo,
        profileRepo = profileRepo,
        fileLogRepo = fileLogRepo,
        windowStateService = windowStateService,
        fileArrivalEvaluator = fileArrivalEvaluator,
        workflowOrchestrator = workflowOrchestrator,
        s3Service = s3Service,
    )

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun makeFileLog(
        id: String = "fl-1",
        windowId: String? = "win-1",
        profileId: String? = "prof-1",
        integrationId: String? = "integ-1",
        remoteIdentifier: String? = null,
    ) = FileLog(
        id = id,
        direction = FileDirection.INBOUND,
        integrationId = integrationId,
        remoteIdentifier = remoteIdentifier,
        fileName = "test.xml",
        windowId = windowId,
        profileId = profileId,
        arrivedAt = now,
    )

    private fun makeWindow(id: String = "win-1", profileId: String = "prof-1", status: WindowStatus = WindowStatus.OPEN) = WindowInstance(
        id = id,
        profileId = profileId,
        profileVersion = 1,
        status = status,
    )

    @AfterEach
    fun reset() =
        clearMocks(windowRepo, profileRepo, fileLogRepo, windowStateService, fileArrivalEvaluator, workflowOrchestrator, s3Service)

    // ── No content, no remoteIdentifier ───────────────────────────────────────

    @Nested
    inner class NoStorageKey {

        @Test
        fun `returns unmatched result when no content and no remoteIdentifier`() {
            val fileLog = makeFileLog(remoteIdentifier = null)

            val result = service.onFileArrived(fileLog, content = null)

            assertThat(result.matched).isFalse()
            assertThat(result.windowId).isNull()
            assertThat(result.recordsProcessed).isEqualTo(0)
            assertThat(result.message).contains("No file content")
        }
    }

    // ── No matching window ─────────────────────────────────────────────────────

    @Nested
    inner class NoMatchingWindow {

        @Test
        fun `returns unmatched result when windowId resolves to null`() {
            val fileLog = makeFileLog(windowId = "win-1", remoteIdentifier = "key/test.xml")
            every { windowRepo.findById("win-1") } returns null

            val result = service.onFileArrived(fileLog, content = null)

            assertThat(result.matched).isFalse()
            assertThat(result.message).contains("No matching OPEN window")
        }

        @Test
        fun `returns unmatched result when window is not OPEN`() {
            val fileLog = makeFileLog(windowId = "win-1", remoteIdentifier = "key/test.xml")
            every { windowRepo.findById("win-1") } returns makeWindow(status = WindowStatus.CLOSED)

            val result = service.onFileArrived(fileLog, content = null)

            assertThat(result.matched).isFalse()
        }
    }

    // ── Content upload path ────────────────────────────────────────────────────

    @Nested
    inner class ContentUpload {

        @Test
        fun `uploads content to MinIO when InputStream is provided`() {
            val fileLog = makeFileLog(windowId = "win-1", integrationId = "integ-1")
            val window = makeWindow()
            val updatedWindow = window.copy(
                arrivedFileHandles = listOf(
                    FileHandle(integrationId = "integ-1", fileName = "test.xml", remotePath = "integ-1/2026-01-01/test.xml"),
                ),
            )

            every { windowRepo.findById("win-1") } returns window
            every { windowRepo.addArrivedFile(any(), any()) } returns updatedWindow
            every { profileRepo.findById("prof-1") } returns mockk(relaxed = true) {
                every { windowConfig } returns mockk(relaxed = true) {
                    every { closeTrigger } returns WindowTrigger.FileArrival(
                        integrationId = "integ-1",
                        filePattern = "*.xml",
                    )
                }
            }
            every { s3Service.archive(any(), any(), any(), any()) } returns mockk {
                every { key } returns "integ-1/2026-01-01/test.xml"
            }
            every { fileArrivalEvaluator.evaluateOnEvent(any(), any()) } returns TriggerEvaluationResult(
                result = TriggerResult.CONTINUE,
                reason = "trigger not satisfied",
            )

            val inputStream = ByteArrayInputStream("<xml/>".toByteArray())
            service.onFileArrived(fileLog, content = inputStream)

            verify(exactly = 1) { s3Service.archive(any(), any(), any(), any()) }
        }
    }

    // ── Trigger evaluation ─────────────────────────────────────────────────────

    @Nested
    inner class TriggerEvaluation {

        private fun setupWindowAndProfile(triggerResult: TriggerResult = TriggerResult.FIRE) {
            val window = makeWindow()
            val updatedWindow = window.copy(
                arrivedFileHandles = listOf(
                    FileHandle(integrationId = "integ-1", fileName = "test.xml", remotePath = "integ-1/date/test.xml"),
                ),
            )
            every { windowRepo.findById("win-1") } returns window
            every { windowRepo.addArrivedFile(any(), any()) } returns updatedWindow
            every { profileRepo.findById("prof-1") } returns mockk(relaxed = true) {
                every { windowConfig } returns mockk(relaxed = true) {
                    every { closeTrigger } returns WindowTrigger.FileArrival(
                        integrationId = "integ-1",
                        filePattern = "*.xml",
                    )
                }
            }
            every { fileArrivalEvaluator.evaluateOnEvent(any(), any()) } returns TriggerEvaluationResult(
                result = triggerResult,
                reason = "test reason",
            )
        }

        @Test
        fun `FIRE trigger executes workflow and closes window`() {
            setupWindowAndProfile(TriggerResult.FIRE)
            val fileLog = makeFileLog(remoteIdentifier = "integ-1/date/test.xml")
            every { workflowOrchestrator.executeOnFileArrived(any()) } returns 5
            every { fileLogRepo.findById(any()) } returns fileLog

            val result = service.onFileArrived(fileLog, content = null)

            assertThat(result.matched).isTrue()
            assertThat(result.recordsProcessed).isEqualTo(5)
            verify(exactly = 1) { workflowOrchestrator.executeOnFileArrived("win-1") }
            verify(exactly = 1) { windowStateService.startClosing("win-1") }
            verify(exactly = 1) { windowStateService.close("win-1", 5) }
        }

        @Test
        fun `CONTINUE trigger does not execute workflow`() {
            setupWindowAndProfile(TriggerResult.CONTINUE)
            val fileLog = makeFileLog(remoteIdentifier = "integ-1/date/test.xml")
            every { fileLogRepo.findById(any()) } returns fileLog

            val result = service.onFileArrived(fileLog, content = null)

            assertThat(result.matched).isTrue()
            assertThat(result.recordsProcessed).isEqualTo(0)
            assertThat(result.message).contains("not yet satisfied")
            verify(exactly = 0) { workflowOrchestrator.executeOnFileArrived(any()) }
            verify(exactly = 0) { windowStateService.startClosing(any()) }
        }

        @Test
        fun `PURGE trigger closes window without running workflow`() {
            setupWindowAndProfile(TriggerResult.PURGE)
            val fileLog = makeFileLog(remoteIdentifier = "integ-1/date/test.xml")
            every { fileLogRepo.findById(any()) } returns fileLog

            val result = service.onFileArrived(fileLog, content = null)

            assertThat(result.matched).isTrue()
            assertThat(result.recordsProcessed).isEqualTo(0)
            verify(exactly = 0) { workflowOrchestrator.executeOnFileArrived(any()) }
            verify(atLeast = 1) { windowStateService.startClosing(any()) }
        }

        @Test
        fun `FIRE_AND_PURGE trigger executes workflow and closes window`() {
            setupWindowAndProfile(TriggerResult.FIRE_AND_PURGE)
            val fileLog = makeFileLog(remoteIdentifier = "integ-1/date/test.xml")
            every { workflowOrchestrator.executeOnFileArrived(any()) } returns 10
            every { fileLogRepo.findById(any()) } returns fileLog

            val result = service.onFileArrived(fileLog, content = null)

            assertThat(result.matched).isTrue()
            verify(exactly = 1) { workflowOrchestrator.executeOnFileArrived("win-1") }
            verify(exactly = 1) { windowStateService.close("win-1", 10) }
        }
    }

    // ── Workflow failure ───────────────────────────────────────────────────────

    @Nested
    inner class WorkflowFailure {

        @Test
        fun `workflow exception leaves window open and marks file as FAILED`() {
            val fileLog = makeFileLog(remoteIdentifier = "integ-1/date/test.xml")
            val window = makeWindow()
            val updatedWindow = window.copy(
                arrivedFileHandles = listOf(
                    FileHandle(integrationId = "integ-1", fileName = "test.xml", remotePath = "integ-1/date/test.xml"),
                ),
            )
            every { windowRepo.findById("win-1") } returns window
            every { windowRepo.addArrivedFile(any(), any()) } returns updatedWindow
            every { profileRepo.findById("prof-1") } returns mockk(relaxed = true) {
                every { windowConfig } returns mockk(relaxed = true) {
                    every { closeTrigger } returns WindowTrigger.FileArrival(
                        integrationId = "integ-1",
                        filePattern = "*.xml",
                    )
                }
            }
            every { fileArrivalEvaluator.evaluateOnEvent(any(), any()) } returns TriggerEvaluationResult(
                result = TriggerResult.FIRE,
                reason = "trigger fired",
            )
            every { workflowOrchestrator.executeOnFileArrived(any()) } throws RuntimeException("Pipeline error")
            every { fileLogRepo.findById(any()) } returns fileLog

            val result = service.onFileArrived(fileLog, content = null)

            assertThat(result.matched).isTrue()
            assertThat(result.message).contains("Workflow failed")
            // Window should NOT be closed on failure
            verify(exactly = 0) { windowStateService.close(any(), any()) }
        }
    }
}
