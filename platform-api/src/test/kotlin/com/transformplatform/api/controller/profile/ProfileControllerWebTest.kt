package com.transformplatform.api.controller.profile

import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.api.service.profile.ProfileService
import com.transformplatform.common.domain.profile.Profile
import com.transformplatform.common.domain.profile.ProfileStatus
import com.transformplatform.common.domain.trigger.WindowTrigger
import com.transformplatform.common.domain.window.OverlapPolicy
import com.transformplatform.common.domain.window.WindowConfig
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant

/**
 * Web-layer (MVC slice) tests for [ProfileController].
 *
 * Covers HTTP routing, status codes, and response shape for all endpoints,
 * with focus on the new [ProfileController.validateProfile] endpoint.
 */
@WebMvcTest(
    controllers = [ProfileController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
    ],
)
@Import(ProfileControllerWebTest.MockBeans::class)
class ProfileControllerWebTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var profileService: ProfileService

    @TestConfiguration
    class MockBeans {
        @Bean fun profileService() = mockk<ProfileService>(relaxed = true)
    }

    private val now = Instant.parse("2026-01-01T12:00:00Z")

    private val manualConfig = WindowConfig(
        openTrigger = WindowTrigger.Manual(),
        closeTrigger = WindowTrigger.Manual(),
        overlapPolicy = OverlapPolicy.SKIP_NEW,
    )

    private fun draftProfile(id: String = "prof-1") = Profile(
        id = id,
        name = "Test Profile",
        clientId = "client-a",
        description = "A test profile",
        status = ProfileStatus.DRAFT,
        version = 1,
        windowConfig = manualConfig,
        actions = emptyList(),
        tags = emptyMap(),
        createdBy = "test",
        updatedBy = "test",
        createdAt = now,
        updatedAt = now,
    )

    private fun enabledProfile(id: String = "prof-1") = draftProfile(id).copy(status = ProfileStatus.ENABLED)

    // ── GET /api/profiles ─────────────────────────────────────────────────────

    @Nested
    inner class ListProfiles {

        @Test
        fun `GET list returns 200 with empty array when no profiles`() {
            every { profileService.findAll(any(), any()) } returns emptyList()
            mockMvc.perform(get("/api/profiles"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$").isArray)
        }

        @Test
        fun `GET list with clientId filter returns 200`() {
            every { profileService.findAll(clientId = "client-a", status = null) } returns listOf(draftProfile())
            mockMvc.perform(get("/api/profiles").param("clientId", "client-a"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].clientId").value("client-a"))
        }
    }

    // ── GET /api/profiles/{id} ────────────────────────────────────────────────

    @Nested
    inner class GetProfile {

        @Test
        fun `GET by id returns 200 with full profile`() {
            every { profileService.findById("prof-1") } returns draftProfile()
            mockMvc.perform(get("/api/profiles/prof-1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value("prof-1"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
        }

        @Test
        fun `GET by unknown id propagates NoSuchElementException as 404`() {
            every { profileService.findById("ghost") } throws NoSuchElementException("Profile ghost not found")
            mockMvc.perform(get("/api/profiles/ghost"))
                .andExpect(status().isNotFound)
        }
    }

    // ── POST /api/profiles ────────────────────────────────────────────────────

    @Nested
    inner class CreateProfile {

        private val createJson = """
            {
              "name": "Test Profile",
              "clientId": "client-a",
              "windowConfig": {
                "openTrigger": {"type": "MANUAL"},
                "closeTrigger": {"type": "MANUAL"},
                "overlapPolicy": "SKIP_NEW"
              },
              "actions": []
            }
        """.trimIndent()

        @Test
        fun `POST create returns 201 with created profile`() {
            every { profileService.create(any()) } returns draftProfile()
            mockMvc.perform(
                post("/api/profiles")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createJson),
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.id").value("prof-1"))
        }
    }

    // ── POST /api/profiles/{id}/enable ────────────────────────────────────────

    @Nested
    inner class EnableProfile {

        @Test
        fun `POST enable returns 200 with ENABLED profile`() {
            every { profileService.enable("prof-1") } returns Pair(enabledProfile(), null)
            mockMvc.perform(post("/api/profiles/prof-1/enable"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("ENABLED"))
        }

        @Test
        fun `POST enable throws when profile already enabled`() {
            every { profileService.enable("prof-1") } throws IllegalStateException("already ENABLED")
            mockMvc.perform(post("/api/profiles/prof-1/enable"))
                .andExpect(status().isConflict)
        }
    }

    // ── POST /api/profiles/{id}/disable ──────────────────────────────────────

    @Nested
    inner class DisableProfile {

        @Test
        fun `POST disable returns 200 with DISABLED profile`() {
            every { profileService.disable("prof-1") } returns enabledProfile().copy(status = ProfileStatus.DISABLED)
            mockMvc.perform(post("/api/profiles/prof-1/disable"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("DISABLED"))
        }
    }

    // ── DELETE /api/profiles/{id} ─────────────────────────────────────────────

    @Nested
    inner class DeleteProfile {

        @Test
        fun `DELETE returns 204 No Content`() {
            mockMvc.perform(delete("/api/profiles/prof-1"))
                .andExpect(status().isNoContent)
        }
    }

    // ── POST /api/profiles/{id}/validate ─────────────────────────────────────

    @Nested
    inner class ValidateProfile {

        private val validUpdateJson = """
            {
              "name": "Updated Profile",
              "clientId": "client-a",
              "windowConfig": {
                "openTrigger": {"type": "MANUAL"},
                "closeTrigger": {"type": "MANUAL"},
                "overlapPolicy": "SKIP_NEW"
              },
              "actions": []
            }
        """.trimIndent()

        @Test
        fun `POST validate returns 200 with valid=true for valid config`() {
            every { profileService.findById("prof-1") } returns draftProfile()
            every { profileService.validate(any()) } returns emptyList()

            mockMvc.perform(
                post("/api/profiles/prof-1/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validUpdateJson),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors").isArray)
                .andExpect(jsonPath("$.errors").isEmpty)
        }

        @Test
        fun `POST validate returns 422 with valid=false and errors for invalid config`() {
            every { profileService.findById("prof-1") } returns draftProfile()
            every { profileService.validate(any()) } returns listOf("windowConfig.closeTrigger is required")

            mockMvc.perform(
                post("/api/profiles/prof-1/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validUpdateJson),
            )
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors[0]").value("windowConfig.closeTrigger is required"))
        }

        @Test
        fun `POST validate returns 404 when profile does not exist`() {
            every { profileService.findById("ghost") } throws NoSuchElementException("Profile ghost not found")

            mockMvc.perform(
                post("/api/profiles/ghost/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validUpdateJson),
            )
                .andExpect(status().isNotFound)
        }
    }
}
