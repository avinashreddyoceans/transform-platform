package com.transformplatform.api.controller.filelog

import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.api.service.workflow.FileArrivalHandlerService
import com.transformplatform.api.service.workflow.FileArrivalResult
import com.transformplatform.common.domain.filelog.FileDirection
import com.transformplatform.common.domain.filelog.FileLog
import com.transformplatform.common.domain.filelog.FileLogStatus
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.domain.window.WindowStatus
import com.transformplatform.scheduler.repository.FileLogRepository
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * Web-layer (MVC slice) tests for [FileLogController].
 *
 * Covers HTTP routing, status codes, and response shape for all endpoints,
 * with focus on the new [FileLogController.submitFileToWindow] endpoint.
 */
@WebMvcTest(
    controllers = [FileLogController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
    ],
)
@Import(FileLogControllerWebTest.MockBeans::class)
class FileLogControllerWebTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var fileLogRepo: FileLogRepository

    @Autowired lateinit var windowRepo: WindowInstanceRepository

    @Autowired lateinit var fileArrivalHandler: FileArrivalHandlerService

    @TestConfiguration
    class MockBeans {
        @Bean fun fileLogRepo() = mockk<FileLogRepository>(relaxed = true)

        @Bean fun windowRepo() = mockk<WindowInstanceRepository>(relaxed = true)

        @Bean fun fileArrivalHandler() = mockk<FileArrivalHandlerService>(relaxed = true)
    }

    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private fun makeFileLog(
        id: String = "fl-1",
        windowId: String? = "win-1",
        fileName: String = "test.xml",
        status: FileLogStatus = FileLogStatus.RECEIVED,
    ) = FileLog(
        id = id,
        direction = FileDirection.INBOUND,
        integrationId = "integ-1",
        fileName = fileName,
        windowId = windowId,
        arrivedAt = now,
        status = status,
    )

    private fun openWindow(id: String = "win-1") = WindowInstance(
        id = id,
        profileId = "prof-1",
        profileVersion = 1,
        status = WindowStatus.OPEN,
        openedAt = now,
    )

    // ── GET /api/files ─────────────────────────────────────────────────────────

    @Nested
    inner class ListFiles {

        @Test
        fun `GET list returns 200 with empty array`() {
            every { fileLogRepo.findAll(any()) } returns emptyList()
            mockMvc.perform(get("/api/files"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
        }

        @Test
        fun `GET list with direction=INBOUND returns filtered results`() {
            every { fileLogRepo.findByDirection(FileDirection.INBOUND, any()) } returns listOf(makeFileLog())
            mockMvc.perform(get("/api/files").param("direction", "INBOUND"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].direction").value("INBOUND"))
        }

        @Test
        fun `GET list with invalid direction returns 400`() {
            mockMvc.perform(get("/api/files").param("direction", "SIDEWAYS"))
                .andExpect(status().isBadRequest)
        }
    }

    // ── GET /api/files/{id} ────────────────────────────────────────────────────

    @Nested
    inner class GetFile {

        @Test
        fun `GET by id returns 200 with file log`() {
            every { fileLogRepo.findById("fl-1") } returns makeFileLog()
            mockMvc.perform(get("/api/files/fl-1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value("fl-1"))
                .andExpect(jsonPath("$.fileName").value("test.xml"))
        }

        @Test
        fun `GET by unknown id returns 404`() {
            every { fileLogRepo.findById("no-such-id") } returns null
            mockMvc.perform(get("/api/files/no-such-id"))
                .andExpect(status().isNotFound)
        }
    }

    // ── POST /api/windows/{windowId}/submit-file ───────────────────────────────

    @Nested
    inner class SubmitFileToWindow {

        @Test
        fun `POST submit-file returns 200 with processing result`() {
            val fileLog = makeFileLog()
            every { windowRepo.findById("win-1") } returns openWindow()
            every { fileLogRepo.existsInbound(any(), any(), any()) } returns false
            every { fileLogRepo.save(any()) } returns fileLog
            every { fileArrivalHandler.onFileArrived(any(), any()) } returns FileArrivalResult(
                matched = true,
                windowId = "win-1",
                recordsProcessed = 14,
                message = "Window closed after processing 14 record(s)",
            )

            val xmlFile = MockMultipartFile("file", "camt053.xml", "application/xml", "<xml/>".toByteArray())

            mockMvc.perform(
                multipart("/api/windows/win-1/submit-file").file(xmlFile),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.windowId").value("win-1"))
                .andExpect(jsonPath("$.fileName").value("camt053.xml"))
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.recordsProcessed").value(14))
        }

        @Test
        fun `POST submit-file returns 404 when window not found`() {
            every { windowRepo.findById("ghost") } returns null

            val xmlFile = MockMultipartFile("file", "test.xml", "application/xml", "<xml/>".toByteArray())

            mockMvc.perform(
                multipart("/api/windows/ghost/submit-file").file(xmlFile),
            )
                .andExpect(status().isNotFound)
        }

        @Test
        fun `POST submit-file returns 409 when window is not OPEN`() {
            every { windowRepo.findById("win-1") } returns WindowInstance(
                id = "win-1",
                profileId = "prof-1",
                profileVersion = 1,
                status = WindowStatus.CLOSED,
            )

            val xmlFile = MockMultipartFile("file", "test.xml", "application/xml", "<xml/>".toByteArray())

            mockMvc.perform(
                multipart("/api/windows/win-1/submit-file").file(xmlFile),
            )
                .andExpect(status().isConflict)
        }

        @Test
        fun `POST submit-file returns 409 when file already submitted`() {
            every { windowRepo.findById("win-1") } returns openWindow()
            every { fileLogRepo.existsInbound("win-1", any(), "test.xml") } returns true

            val xmlFile = MockMultipartFile("file", "test.xml", "application/xml", "<xml/>".toByteArray())

            mockMvc.perform(
                multipart("/api/windows/win-1/submit-file").file(xmlFile),
            )
                .andExpect(status().isConflict)
        }

        @Test
        fun `POST submit-file accepts optional integrationId parameter`() {
            val fileLog = makeFileLog()
            every { windowRepo.findById("win-1") } returns openWindow()
            every { fileLogRepo.existsInbound(any(), any(), any()) } returns false
            every { fileLogRepo.save(any()) } returns fileLog
            every { fileArrivalHandler.onFileArrived(any(), any()) } returns FileArrivalResult(
                matched = true,
                windowId = "win-1",
                recordsProcessed = 5,
                message = "Window closed",
            )

            val xmlFile = MockMultipartFile("file", "test.xml", "application/xml", "<xml/>".toByteArray())

            mockMvc.perform(
                multipart("/api/windows/win-1/submit-file")
                    .file(xmlFile)
                    .param("integrationId", "my-integration"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.recordsProcessed").value(5))
        }
    }
}
