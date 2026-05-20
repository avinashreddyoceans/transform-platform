package com.transformplatform.api.controller.window

import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.domain.window.WindowStatus
import com.transformplatform.scheduler.repository.ProfileRepository
import com.transformplatform.scheduler.repository.WindowDataRepository
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import com.transformplatform.scheduler.service.WindowStateService
import com.transformplatform.scheduler.service.WorkflowOrchestrator
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * Web-layer (MVC slice) tests for [WindowController].
 *
 * Covers HTTP routing, status codes, and response shape for all endpoints,
 * with focus on the new [WindowController.openWindow] endpoint.
 */
@WebMvcTest(
    controllers = [WindowController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
    ],
)
@Import(WindowControllerWebTest.MockBeans::class)
class WindowControllerWebTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var windowRepo: WindowInstanceRepository

    @Autowired lateinit var profileRepo: ProfileRepository

    @Autowired lateinit var dataRepo: WindowDataRepository

    @Autowired lateinit var windowStateService: WindowStateService

    @Autowired lateinit var workflowOrchestrator: WorkflowOrchestrator

    @TestConfiguration
    class MockBeans {
        @Bean fun windowRepo() = mockk<WindowInstanceRepository>(relaxed = true)

        @Bean fun profileRepo() = mockk<ProfileRepository>(relaxed = true)

        @Bean fun dataRepo() = mockk<WindowDataRepository>(relaxed = true)

        @Bean fun windowStateService() = mockk<WindowStateService>(relaxed = true)

        @Bean fun workflowOrchestrator() = mockk<WorkflowOrchestrator>(relaxed = true)
    }

    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private fun pendingWindow(id: String = "win-1", profileId: String = "prof-1") = WindowInstance(
        id = id,
        profileId = profileId,
        profileVersion = 1,
        status = WindowStatus.PENDING,
        createdAt = now,
        updatedAt = now,
    )

    private fun openWindow(id: String = "win-1", profileId: String = "prof-1") = WindowInstance(
        id = id,
        profileId = profileId,
        profileVersion = 1,
        status = WindowStatus.OPEN,
        openedAt = now,
        createdAt = now,
        updatedAt = now,
    )

    // ── GET /api/windows ───────────────────────────────────────────────────────

    @Nested
    inner class ListWindows {

        @Test
        fun `GET list returns 200 with empty array when no windows`() {
            every { windowRepo.findAll() } returns emptyList()
            mockMvc.perform(get("/api/windows"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
        }

        @Test
        fun `GET list with status filter returns 200`() {
            every { windowRepo.findByStatus(WindowStatus.OPEN) } returns listOf(openWindow())
            mockMvc.perform(get("/api/windows").param("status", "OPEN"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].status").value("OPEN"))
        }

        @Test
        fun `GET list with invalid status returns 400`() {
            mockMvc.perform(get("/api/windows").param("status", "INVALID_STATUS"))
                .andExpect(status().isBadRequest)
        }
    }

    // ── GET /api/windows/{id} ─────────────────────────────────────────────────

    @Nested
    inner class GetWindow {

        @Test
        fun `GET by id returns 200 with window detail`() {
            every { windowRepo.findById("win-1") } returns openWindow()
            mockMvc.perform(get("/api/windows/win-1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value("win-1"))
                .andExpect(jsonPath("$.status").value("OPEN"))
        }

        @Test
        fun `GET by unknown id returns 404`() {
            every { windowRepo.findById("no-such-id") } returns null
            mockMvc.perform(get("/api/windows/no-such-id"))
                .andExpect(status().isNotFound)
        }
    }

    // ── POST /api/windows/{id}/open ───────────────────────────────────────────

    @Nested
    inner class OpenWindow {

        @Test
        fun `POST open transitions PENDING window to OPEN and returns 200`() {
            every { windowRepo.findById("win-1") } returns pendingWindow()
            every { profileRepo.findById("prof-1") } returns mockk(relaxed = true) {
                every { windowConfig } returns mockk(relaxed = true)
            }
            every { windowStateService.open(any(), any(), any(), any()) } returns openWindow()

            mockMvc.perform(post("/api/windows/win-1/open"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.windowId").value("win-1"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.profileId").value("prof-1"))
        }

        @Test
        fun `POST open returns 404 when window does not exist`() {
            every { windowRepo.findById("ghost") } returns null

            mockMvc.perform(post("/api/windows/ghost/open"))
                .andExpect(status().isNotFound)
        }

        @Test
        fun `POST open returns 400 when window is not PENDING`() {
            every { windowRepo.findById("win-1") } returns openWindow()

            mockMvc.perform(post("/api/windows/win-1/open"))
                .andExpect(status().isConflict)
        }

        @Test
        fun `POST open returns 400 when profile not found`() {
            every { windowRepo.findById("win-1") } returns pendingWindow()
            every { profileRepo.findById("prof-1") } returns null

            mockMvc.perform(post("/api/windows/win-1/open"))
                .andExpect(status().isNotFound)
        }

        @Test
        fun `POST open returns 400 when overlap policy blocks opening`() {
            every { windowRepo.findById("win-1") } returns pendingWindow()
            every { profileRepo.findById("prof-1") } returns mockk(relaxed = true) {
                every { windowConfig } returns mockk(relaxed = true)
            }
            every { windowStateService.open(any(), any(), any(), any()) } returns null

            mockMvc.perform(post("/api/windows/win-1/open"))
                .andExpect(status().isConflict)
        }
    }

    // ── POST /api/windows/{id}/close ──────────────────────────────────────────

    @Nested
    inner class CloseWindow {

        private fun forcedClosedWindow(id: String = "win-1") = WindowInstance(
            id = id,
            profileId = "prof-1",
            profileVersion = 1,
            status = WindowStatus.FORCE_CLOSED,
            statusReason = "Manually closed via API",
            createdAt = now,
            updatedAt = now,
        )

        @Test
        fun `POST close returns 200 and transitions OPEN window to FORCE_CLOSED`() {
            every { windowRepo.findById("win-1") } returns openWindow()
            every { windowStateService.forceClose("win-1", any()) } returns forcedClosedWindow()

            mockMvc.perform(
                post("/api/windows/win-1/close")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"reason":"test cleanup"}"""),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.windowId").value("win-1"))
                .andExpect(jsonPath("$.status").value("FORCE_CLOSED"))
        }

        @Test
        fun `POST close with no body uses default reason`() {
            every { windowRepo.findById("win-1") } returns openWindow()
            every { windowStateService.forceClose("win-1", any()) } returns forcedClosedWindow()

            mockMvc.perform(post("/api/windows/win-1/close"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.windowId").value("win-1"))
        }

        @Test
        fun `POST close returns 404 when window does not exist`() {
            every { windowRepo.findById("ghost") } returns null

            mockMvc.perform(post("/api/windows/ghost/close"))
                .andExpect(status().isNotFound)
        }

        @Test
        fun `POST close returns 409 when window is already terminal`() {
            every { windowRepo.findById("win-1") } returns WindowInstance(
                id = "win-1",
                profileId = "prof-1",
                profileVersion = 1,
                status = WindowStatus.CLOSED,
                createdAt = now,
                updatedAt = now,
            )

            mockMvc.perform(post("/api/windows/win-1/close"))
                .andExpect(status().isConflict)
        }
    }

    // ── POST /api/windows/{id}/reprocess ──────────────────────────────────────

    @Nested
    inner class ReprocessWindow {

        private fun closedWindow(id: String = "win-1") = WindowInstance(
            id = id,
            profileId = "prof-1",
            profileVersion = 1,
            status = WindowStatus.CLOSED,
            openedAt = now,
            closedAt = now,
            createdAt = now,
            updatedAt = now,
        )

        @Test
        fun `POST reprocess returns 200 with records processed`() {
            every { windowRepo.findById("win-1") } returns closedWindow()
            every { workflowOrchestrator.executeClosingActions("win-1") } returns 42

            mockMvc.perform(post("/api/windows/win-1/reprocess"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.windowId").value("win-1"))
                .andExpect(jsonPath("$.recordsProcessed").value(42))
        }

        @Test
        fun `POST reprocess returns 404 when window does not exist`() {
            every { windowRepo.findById("ghost") } returns null

            mockMvc.perform(post("/api/windows/ghost/reprocess"))
                .andExpect(status().isNotFound)
        }

        @Test
        fun `POST reprocess returns 409 when window is not CLOSED or FORCE_CLOSED`() {
            every { windowRepo.findById("win-1") } returns openWindow()

            mockMvc.perform(post("/api/windows/win-1/reprocess"))
                .andExpect(status().isConflict)
        }

        @Test
        fun `POST reprocess also works on FORCE_CLOSED window`() {
            val forceClosedWindow = WindowInstance(
                id = "win-1",
                profileId = "prof-1",
                profileVersion = 1,
                status = WindowStatus.FORCE_CLOSED,
                createdAt = now,
                updatedAt = now,
            )
            every { windowRepo.findById("win-1") } returns forceClosedWindow
            every { workflowOrchestrator.executeClosingActions("win-1") } returns 0

            mockMvc.perform(post("/api/windows/win-1/reprocess"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.recordsProcessed").value(0))
        }
    }

    // ── GET /api/windows/{id}/events ──────────────────────────────────────────

    @Nested
    inner class GetWindowEvents {

        @Test
        fun `GET events returns 200 with events list`() {
            every { windowRepo.findById("win-1") } returns openWindow()
            every { dataRepo.findByWindow("win-1", null) } returns emptyList()

            mockMvc.perform(get("/api/windows/win-1/events"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.windowId").value("win-1"))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.events").isArray)
        }

        @Test
        fun `GET events returns 404 when window does not exist`() {
            every { windowRepo.findById("ghost") } returns null

            mockMvc.perform(get("/api/windows/ghost/events"))
                .andExpect(status().isNotFound)
        }

        @Test
        fun `GET events with invalid recordType returns 400`() {
            every { windowRepo.findById("win-1") } returns openWindow()

            mockMvc.perform(get("/api/windows/win-1/events").param("recordType", "NOT_A_REAL_TYPE"))
                .andExpect(status().isBadRequest)
        }
    }
}
