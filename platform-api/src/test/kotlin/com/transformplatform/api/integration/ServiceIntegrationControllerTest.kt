// MOVED — tests split into:
//   Web-layer: com.transformplatform.api.controller.ServiceIntegrationControllerWebTest
//   E2E:       com.transformplatform.api.e2e.ServiceIntegrationE2ETest
package com.transformplatform.api.integration

import com.transformplatform.api.controller.integration.CreateIntegrationRequest
import com.transformplatform.api.controller.integration.ServiceIntegrationController
import com.transformplatform.api.controller.integration.ServiceIntegrationResponse
import com.transformplatform.api.controller.integration.UpdateIntegrationRequest
import com.transformplatform.integration.model.Direction
import com.transformplatform.integration.model.FtpDetails
import com.transformplatform.integration.model.IntegrationType
import com.transformplatform.integration.model.S3Details
import com.transformplatform.integration.model.ServiceIntegration
import com.transformplatform.integration.model.SftpDetails
import com.transformplatform.integration.service.IntegrationEncryptionService
import com.transformplatform.integration.service.ServiceIntegrationService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Instant

/**
 * Pure unit tests for [ServiceIntegrationController].
 *
 * No Spring context — controller is instantiated directly with MockK doubles.
 * Covers response mapping, error-handling branches (404/400), and secret masking.
 *
 * HTTP routing and input validation are covered by [ServiceIntegrationControllerWebTest].
 * Full end-to-end behaviour is covered by [com.transformplatform.api.e2e.ServiceIntegrationE2ETest].
 */
class ServiceIntegrationControllerTest {

    private val service = mockk<ServiceIntegrationService>()
    private val encryptionService = mockk<IntegrationEncryptionService>()
    private val controller = ServiceIntegrationController(service, encryptionService)

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private val now = Instant.parse("2026-03-09T00:00:00Z")

    private fun sftpIntegration(id: String = "integ-1", enabled: Boolean = false) = ServiceIntegration(
        id = id,
        type = IntegrationType.SFTP,
        userId = "p1-dev-group",
        shortDescription = "SEPA INBOUND SFTP",
        isEnabled = enabled,
        encryptedDetails = "encrypted_blob",
        updatedBy = "p1-dev-group",
        createdAt = now,
        updatedAt = now,
    )

    private fun s3Integration(id: String = "integ-s3") = ServiceIntegration(
        id = id,
        type = IntegrationType.S3,
        userId = "p1-dev-group",
        shortDescription = "S3 feed bucket",
        isEnabled = true,
        encryptedDetails = "encrypted_blob",
        updatedBy = "p1-dev-group",
        createdAt = now,
        updatedAt = now,
    )

    private val sftpDetails = SftpDetails(
        host = "sftp.bank.com",
        port = 22,
        directories = listOf("outbox"),
        userName = "feed_user",
        password = "s3cr3t",
        direction = Direction.INBOUND,
        filters = listOf("SEPA_CREDIT_*"),
    )

    private val ftpDetails = FtpDetails(
        host = "ftp.bank.com",
        port = 21,
        directories = listOf("/upload"),
        userName = "ftp_user",
        password = "ftp_pass",
        direction = Direction.INBOUND,
        filters = emptyList(),
    )

    private val s3Details = S3Details(
        bucketName = "my-feeds",
        region = "eu-west-1",
        accessKeyId = "AKID1234ABCD",
        secretAccessKey = "supersecretkey",
        prefix = "inbound/",
        direction = Direction.INBOUND,
        filters = emptyList(),
    )

    @AfterEach
    fun reset() = clearMocks(service, encryptionService)

    // ── ServiceIntegrationResponse.from() ─────────────────────────────────────

    @Nested
    inner class ResponseMapping {

        @Test
        fun `from() maps all top-level fields correctly`() {
            val integration = sftpIntegration()
            val response = ServiceIntegrationResponse.from(integration, sftpDetails)

            assertEquals("integ-1", response.id)
            assertEquals("SFTP", response.type)
            assertEquals("p1-dev-group", response.userId)
            assertEquals("SEPA INBOUND SFTP", response.shortDescription)
            assertFalse(response.isEnabled)
            assertEquals("p1-dev-group", response.updatedBy)
            assertEquals(now, response.createdAt)
            assertEquals(now, response.updatedAt)
        }

        @Test
        fun `from() masks SFTP password in details`() {
            val response = ServiceIntegrationResponse.from(sftpIntegration(), sftpDetails)

            assertEquals("***", response.details["password"])
            assertEquals("sftp.bank.com", response.details["host"])
            assertEquals("feed_user", response.details["userName"])
        }

        @Test
        fun `from() masks FTP password in details`() {
            val integration = ServiceIntegration(
                id = "integ-ftp",
                type = IntegrationType.FTP,
                userId = "p1",
                shortDescription = "FTP feed",
                isEnabled = false,
                encryptedDetails = "blob",
                updatedBy = "p1",
                createdAt = now,
                updatedAt = now,
            )
            val response = ServiceIntegrationResponse.from(integration, ftpDetails)

            assertEquals("***", response.details["password"])
            assertEquals("ftp.bank.com", response.details["host"])
        }

        @Test
        fun `from() partially masks S3 accessKeyId and fully masks secretAccessKey`() {
            val response = ServiceIntegrationResponse.from(s3Integration(), s3Details)

            // accessKeyId — first 4 chars visible, rest masked
            val maskedKey = response.details["accessKeyId"] as String
            assertTrue(maskedKey.startsWith("AKID"), "Expected key to start with AKID, got: $maskedKey")
            assertTrue(maskedKey.endsWith("****"))

            assertEquals("***", response.details["secretAccessKey"])
        }

        @Test
        fun `from() never includes encryptedDetails in response`() {
            val response = ServiceIntegrationResponse.from(sftpIntegration(), sftpDetails)
            assertFalse(response.details.containsKey("encryptedDetails"))
        }

        @Test
        fun `from() preserves filters list in details`() {
            val response = ServiceIntegrationResponse.from(sftpIntegration(), sftpDetails)

            @Suppress("UNCHECKED_CAST")
            val filters = response.details["filters"] as List<String>
            assertEquals(listOf("SEPA_CREDIT_*"), filters)
        }

        @Test
        fun `from() preserves S3 prefix in details`() {
            val response = ServiceIntegrationResponse.from(s3Integration(), s3Details)
            assertEquals("inbound/", response.details["prefix"])
        }

        @Test
        fun `from() maps null S3 prefix correctly`() {
            val noPrefix = s3Details.copy(prefix = null)
            val response = ServiceIntegrationResponse.from(s3Integration(), noPrefix)
            assertNull(response.details["prefix"])
        }
    }

    // ── listIntegrations() ────────────────────────────────────────────────────

    @Nested
    inner class ListIntegrations {

        @Test
        fun `returns 200 with empty list when no integrations exist`() {
            every { service.findAll() } returns emptyList()

            val response = controller.listIntegrations()

            assertEquals(HttpStatus.OK, response.statusCode)
            assertTrue(response.body!!.isEmpty())
        }

        @Test
        fun `returns 200 with mapped response for each integration`() {
            val integration = sftpIntegration()
            every { service.findAll() } returns listOf(integration)
            every { encryptionService.decryptDetails(integration) } returns sftpDetails

            val response = controller.listIntegrations()

            assertEquals(HttpStatus.OK, response.statusCode)
            assertEquals(1, response.body!!.size)
            assertEquals("integ-1", response.body!![0].id)
        }

        @Test
        fun `calls findAll() once`() {
            every { service.findAll() } returns emptyList()
            controller.listIntegrations()
            verify(exactly = 1) { service.findAll() }
        }
    }

    // ── getIntegration() ──────────────────────────────────────────────────────

    @Nested
    inner class GetIntegration {

        @Test
        fun `returns 200 with correct body when integration exists`() {
            val integration = sftpIntegration()
            every { service.findById("integ-1") } returns integration
            every { encryptionService.decryptDetails(integration) } returns sftpDetails

            val response = controller.getIntegration("integ-1")

            assertEquals(HttpStatus.OK, response.statusCode)
            assertNotNull(response.body)
            assertEquals("integ-1", response.body!!.id)
            assertEquals("SFTP", response.body!!.type)
        }

        @Test
        fun `returns 404 when service throws NoSuchElementException`() {
            every { service.findById("ghost") } throws NoSuchElementException("not found")

            val response = controller.getIntegration("ghost")

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
            assertNull(response.body)
        }
    }

    // ── createIntegration() ───────────────────────────────────────────────────

    @Nested
    inner class CreateIntegration {

        @Test
        fun `returns 201 with body for valid SFTP request`() {
            val integration = sftpIntegration()
            every { service.create(any(), any(), any(), any(), any(), any()) } returns integration

            val request = CreateIntegrationRequest(
                type = "SFTP",
                userId = "p1-dev-group",
                shortDescription = "SEPA INBOUND SFTP",
                isEnabled = false,
                details = mapOf(
                    "host" to "sftp.bank.com",
                    "port" to 22,
                    "directories" to listOf("outbox"),
                    "userName" to "feed_user",
                    "password" to "s3cr3t",
                ),
                updatedBy = "p1-dev-group",
            )

            val response = controller.createIntegration(request)

            assertEquals(HttpStatus.CREATED, response.statusCode)
            assertNotNull(response.body)
            assertEquals("integ-1", response.body!!.id)
        }

        @Test
        fun `returns 201 with body for valid S3 request`() {
            val integration = s3Integration()
            every { service.create(any(), any(), any(), any(), any(), any()) } returns integration

            val request = CreateIntegrationRequest(
                type = "S3",
                userId = "p1-dev-group",
                shortDescription = "S3 feed bucket",
                isEnabled = true,
                details = mapOf(
                    "bucketName" to "my-feeds",
                    "region" to "eu-west-1",
                    "accessKeyId" to "AKID1234ABCD",
                    "secretAccessKey" to "supersecretkey",
                    "prefix" to "inbound/",
                ),
                updatedBy = "p1-dev-group",
            )

            val response = controller.createIntegration(request)

            assertEquals(HttpStatus.CREATED, response.statusCode)
            assertEquals("integ-s3", response.body!!.id)
        }

        @Test
        fun `returns 400 for unknown integration type`() {
            val request = CreateIntegrationRequest(
                type = "UNKNOWN_TYPE",
                userId = "p1",
                shortDescription = "test",
                details = emptyMap(),
                updatedBy = "p1",
            )

            val response = controller.createIntegration(request)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        }

        @Test
        fun `returns 400 when required SFTP host field is missing`() {
            val request = CreateIntegrationRequest(
                type = "SFTP",
                userId = "p1",
                shortDescription = "test",
                details = mapOf("userName" to "user", "password" to "pass"), // missing host
                updatedBy = "p1",
            )

            val response = controller.createIntegration(request)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        }

        @Test
        fun `returns 400 when required S3 accessKeyId field is missing`() {
            val request = CreateIntegrationRequest(
                type = "S3",
                userId = "p1",
                shortDescription = "test",
                details = mapOf("bucketName" to "bucket", "secretAccessKey" to "key"), // missing accessKeyId
                updatedBy = "p1",
            )

            val response = controller.createIntegration(request)

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        }

        @Test
        fun `delegates to service with correct arguments`() {
            val integration = sftpIntegration()
            every {
                service.create(
                    type = IntegrationType.SFTP,
                    userId = "p1-dev-group",
                    shortDescription = "SEPA INBOUND SFTP",
                    isEnabled = false,
                    details = any(),
                    updatedBy = "p1-dev-group",
                )
            } returns integration

            val request = CreateIntegrationRequest(
                type = "SFTP",
                userId = "p1-dev-group",
                shortDescription = "SEPA INBOUND SFTP",
                isEnabled = false,
                details = mapOf(
                    "host" to "sftp.bank.com",
                    "userName" to "feed_user",
                    "password" to "s3cr3t",
                ),
                updatedBy = "p1-dev-group",
            )

            controller.createIntegration(request)

            verify(exactly = 1) {
                service.create(
                    type = IntegrationType.SFTP,
                    userId = "p1-dev-group",
                    shortDescription = "SEPA INBOUND SFTP",
                    isEnabled = false,
                    details = any(),
                    updatedBy = "p1-dev-group",
                )
            }
        }
    }

    // ── updateIntegration() ───────────────────────────────────────────────────

    @Nested
    inner class UpdateIntegration {

        @BeforeEach
        fun setUp() {
            val integration = sftpIntegration()
            every { service.findById("integ-1") } returns integration
        }

        @Test
        fun `returns 200 with updated body on successful update`() {
            val updated = sftpIntegration().apply { shortDescription = "Updated description" }
            every {
                service.update(
                    id = "integ-1",
                    shortDescription = "Updated description",
                    details = null,
                    updatedBy = "admin",
                )
            } returns updated
            every { encryptionService.decryptDetails(updated) } returns sftpDetails

            val request = UpdateIntegrationRequest(shortDescription = "Updated description", updatedBy = "admin")
            val response = controller.updateIntegration("integ-1", request)

            assertEquals(HttpStatus.OK, response.statusCode)
            assertNotNull(response.body)
        }

        @Test
        fun `returns 404 when integration does not exist`() {
            every { service.findById("ghost") } throws NoSuchElementException("not found")

            val request = UpdateIntegrationRequest(shortDescription = "new desc", updatedBy = "admin")
            val response = controller.updateIntegration("ghost", request)

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        }
    }

    // ── deleteIntegration() ───────────────────────────────────────────────────

    @Nested
    inner class DeleteIntegration {

        @Test
        fun `returns 204 on successful delete`() {
            every { service.delete("integ-1") } returns Unit

            val response = controller.deleteIntegration("integ-1")

            assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
        }

        @Test
        fun `returns 404 when integration does not exist`() {
            every { service.delete("ghost") } throws NoSuchElementException("not found")

            val response = controller.deleteIntegration("ghost")

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        }

        @Test
        fun `calls service delete with correct id`() {
            every { service.delete("integ-1") } returns Unit
            controller.deleteIntegration("integ-1")
            verify(exactly = 1) { service.delete("integ-1") }
        }
    }

    // ── enableIntegration() ───────────────────────────────────────────────────

    @Nested
    inner class EnableIntegration {

        @Test
        fun `returns 200 with isEnabled true on success`() {
            val enabled = sftpIntegration(enabled = true)
            every { service.enable("integ-1", "admin") } returns enabled
            every { encryptionService.decryptDetails(enabled) } returns sftpDetails

            val response = controller.enableIntegration("integ-1", "admin")

            assertEquals(HttpStatus.OK, response.statusCode)
            assertTrue(response.body!!.isEnabled)
        }

        @Test
        fun `returns 404 when integration does not exist`() {
            every { service.enable("ghost", "admin") } throws NoSuchElementException("not found")

            val response = controller.enableIntegration("ghost", "admin")

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        }

        @Test
        fun `returns 400 when integration is already enabled`() {
            every { service.enable("integ-1", "admin") } throws IllegalStateException("already enabled")

            val response = controller.enableIntegration("integ-1", "admin")

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        }
    }

    // ── disableIntegration() ──────────────────────────────────────────────────

    @Nested
    inner class DisableIntegration {

        @Test
        fun `returns 200 with isEnabled false on success`() {
            val disabled = sftpIntegration(enabled = false)
            every { service.disable("integ-1", "admin") } returns disabled
            every { encryptionService.decryptDetails(disabled) } returns sftpDetails

            val response = controller.disableIntegration("integ-1", "admin")

            assertEquals(HttpStatus.OK, response.statusCode)
            assertFalse(response.body!!.isEnabled)
        }

        @Test
        fun `returns 404 when integration does not exist`() {
            every { service.disable("ghost", "admin") } throws NoSuchElementException("not found")

            val response = controller.disableIntegration("ghost", "admin")

            assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        }

        @Test
        fun `returns 400 when integration is already disabled`() {
            every { service.disable("integ-1", "admin") } throws IllegalStateException("already disabled")

            val response = controller.disableIntegration("integ-1", "admin")

            assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        }
    }
}
