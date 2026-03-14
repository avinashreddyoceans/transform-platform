package com.transformplatform.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.api.controller.integration.ServiceIntegrationController
import com.transformplatform.integration.model.IntegrationType
import com.transformplatform.integration.model.ServiceIntegration
import com.transformplatform.integration.model.SftpDetails
import com.transformplatform.integration.service.IntegrationEncryptionService
import com.transformplatform.integration.service.ServiceIntegrationService
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
 * Web-layer (MVC slice) tests for [ServiceIntegrationController].
 *
 * Purpose: fast, no-database tests that verify:
 *   - HTTP routing (correct URL → correct controller method → correct status)
 *   - Input validation (missing required fields → 400, invalid enum values → 400)
 *   - Response shape (password masking, JSON field names)
 *
 * Full end-to-end behaviour is covered by [com.transformplatform.api.e2e.ServiceIntegrationE2ETest]
 * (Testcontainers + real PostgreSQL/MinIO).
 */
@WebMvcTest(
    controllers = [ServiceIntegrationController::class],
    excludeAutoConfiguration = [
        org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration::class,
    ],
)
@Import(ServiceIntegrationControllerWebTest.MockBeans::class)
class ServiceIntegrationControllerWebTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var mapper: ObjectMapper

    @Autowired lateinit var service: ServiceIntegrationService

    @Autowired lateinit var encryptionService: IntegrationEncryptionService

    // ── Test doubles ──────────────────────────────────────────────────────────

    @TestConfiguration
    class MockBeans {
        @Bean fun service() = mockk<ServiceIntegrationService>(relaxed = true)

        @Bean fun encryptionService() = mockk<IntegrationEncryptionService>(relaxed = true)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun sftpIntegration(id: String = "integ-1", enabled: Boolean = false) = ServiceIntegration(
        id = id,
        type = IntegrationType.SFTP,
        userId = "p1-dev-group",
        shortDescription = "SEPA INBOUND SFTP",
        isEnabled = enabled,
        encryptedDetails = "encrypted_blob",
        updatedBy = "p1-dev-group",
        createdAt = Instant.parse("2026-03-09T00:00:00Z"),
        updatedAt = Instant.parse("2026-03-09T00:00:00Z"),
    )

    private fun sftpDetails(password: String = "s3cr3t") = SftpDetails(
        host = "sftp.bank.com",
        port = 22,
        directories = listOf("outbox"),
        userName = "feed_user",
        password = password,
        filters = listOf("SEPA_CREDIT_*"),
    )

    // ─────────────────────────────────────────────────────────────────────────
    // 1. Input validation
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class InputValidation {

        @Test
        fun `POST returns 400 when type is an unknown value`() {
            val body = mapper.writeValueAsString(
                mapOf(
                    "type" to "FOOBAR",
                    "userId" to "p1",
                    "shortDescription" to "test",
                    "details" to emptyMap<String, Any>(),
                    "updatedBy" to "p1",
                ),
            )
            mockMvc.perform(
                post("/api/v1/integrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
        }

        @Test
        fun `POST returns 400 when required SFTP fields are missing`() {
            // SFTP requires host, userName, password — provide none
            val body = mapper.writeValueAsString(
                mapOf(
                    "type" to "SFTP",
                    "userId" to "p1",
                    "shortDescription" to "test",
                    "details" to emptyMap<String, Any>(),
                    "updatedBy" to "p1",
                ),
            )
            mockMvc.perform(
                post("/api/v1/integrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
        }

        @Test
        fun `POST returns 400 when type field is blank`() {
            val body = mapper.writeValueAsString(
                mapOf(
                    "type" to "   ",
                    "userId" to "p1",
                    "shortDescription" to "test",
                    "details" to emptyMap<String, Any>(),
                    "updatedBy" to "p1",
                ),
            )
            mockMvc.perform(
                post("/api/v1/integrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
        }

        @Test
        fun `POST returns 400 when userId is blank`() {
            val body = mapper.writeValueAsString(
                mapOf(
                    "type" to "SFTP",
                    "userId" to "",
                    "shortDescription" to "test",
                    "details" to mapOf("host" to "h", "userName" to "u", "password" to "p"),
                    "updatedBy" to "admin",
                ),
            )
            mockMvc.perform(
                post("/api/v1/integrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. Response shape — password masking
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class ResponseShape {

        @Test
        fun `GET list masks password field in response`() {
            val integration = sftpIntegration()
            every { service.findAll() } returns listOf(integration)
            every { encryptionService.decryptDetails(integration) } returns sftpDetails(password = "real_secret")

            mockMvc.perform(get("/api/v1/integrations"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[0].details.password").value("***"))
                // Verify the real password never leaks
                .andExpect(jsonPath("$[0].details.password").value("***"))
        }

        @Test
        fun `GET single integration masks password in response`() {
            val integration = sftpIntegration()
            every { service.findById("integ-1") } returns integration
            every { encryptionService.decryptDetails(integration) } returns sftpDetails(password = "top_secret_123")

            mockMvc.perform(get("/api/v1/integrations/integ-1"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.details.password").value("***"))
        }

        @Test
        fun `POST create response contains correct top-level fields`() {
            val integration = sftpIntegration()
            every { service.create(any(), any(), any(), any(), any(), any()) } returns integration
            every { encryptionService.decryptDetails(any()) } returns sftpDetails()

            val body = mapper.writeValueAsString(
                mapOf(
                    "type" to "SFTP",
                    "userId" to "p1-dev-group",
                    "shortDescription" to "SEPA INBOUND SFTP",
                    "details" to mapOf(
                        "host" to "sftp.bank.com",
                        "port" to 22,
                        "directories" to listOf("outbox"),
                        "userName" to "feed_user",
                        "password" to "s3cr3t",
                    ),
                    "updatedBy" to "p1-dev-group",
                ),
            )

            mockMvc.perform(
                post("/api/v1/integrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.type").value("SFTP"))
                .andExpect(jsonPath("$.userId").value("p1-dev-group"))
                .andExpect(jsonPath("$.isEnabled").value(false))
                // encryptedDetails must NEVER appear in the response
                .andExpect(jsonPath("$.encryptedDetails").doesNotExist())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. HTTP routing — correct status codes per endpoint
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    inner class HttpRouting {

        @Test
        fun `GET list returns 200`() {
            every { service.findAll() } returns emptyList()
            mockMvc.perform(get("/api/v1/integrations"))
                .andExpect(status().isOk)
        }

        @Test
        fun `GET by unknown id returns 404`() {
            every { service.findById("no-such-id") } throws NoSuchElementException("not found")
            mockMvc.perform(get("/api/v1/integrations/no-such-id"))
                .andExpect(status().isNotFound)
        }

        @Test
        fun `DELETE by id returns 204`() {
            every { service.delete("integ-1") } returns Unit
            mockMvc.perform(delete("/api/v1/integrations/integ-1"))
                .andExpect(status().isNoContent)
        }

        @Test
        fun `DELETE unknown id returns 404`() {
            every { service.delete("ghost") } throws NoSuchElementException("not found")
            mockMvc.perform(delete("/api/v1/integrations/ghost"))
                .andExpect(status().isNotFound)
        }

        @Test
        fun `POST enable returns 200 with isEnabled true`() {
            val enabled = sftpIntegration(enabled = true)
            every { service.enable("integ-1", "admin") } returns enabled
            every { encryptionService.decryptDetails(enabled) } returns sftpDetails()

            mockMvc.perform(
                post("/api/v1/integrations/integ-1/enable")
                    .param("updatedBy", "admin"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.isEnabled").value(true))
        }

        @Test
        fun `POST disable returns 200 with isEnabled false`() {
            val disabled = sftpIntegration(enabled = false)
            every { service.disable("integ-1", "admin") } returns disabled
            every { encryptionService.decryptDetails(disabled) } returns sftpDetails()

            mockMvc.perform(
                post("/api/v1/integrations/integ-1/disable")
                    .param("updatedBy", "admin"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.isEnabled").value(false))
        }
    }
}
