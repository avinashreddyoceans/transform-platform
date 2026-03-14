package com.transformplatform.api.e2e

import com.transformplatform.integration.repository.DownloadedFileRepository
import com.transformplatform.integration.repository.ServiceIntegrationRepository
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * End-to-end integration tests for the Service Integration REST API.
 *
 * Tests exercise the full stack:
 *   REST request → Spring MVC → ServiceIntegrationService → JPA / Flyway DB (PostgreSQL)
 *                                                          → IntegrationEncryptionService (AES-256)
 *                                                          → DynamicRouteManager (mocked)
 *
 * MinIO (Testcontainers) is available but S3ArchivalService is only exercised indirectly
 * through file-upload scenarios; the MinIO bucket existence check runs on startup.
 *
 * Run with: ./gradlew :platform-api:integrationTest
 */
@DisplayName("ServiceIntegration E2E Tests")
class ServiceIntegrationE2ETest : AbstractE2ETest() {

    @Autowired lateinit var integrationRepo: ServiceIntegrationRepository

    @Autowired lateinit var downloadedFileRepo: DownloadedFileRepository

    // ── Test data helpers ──────────────────────────────────────────────────────

    private fun sftpRequest(description: String = "SEPA INBOUND SFTP", enabled: Boolean = false) = mapOf(
        "type" to "SFTP",
        "userId" to "p1-dev-group",
        "shortDescription" to description,
        "isEnabled" to enabled,
        "details" to mapOf(
            "host" to "sftp.bank.com",
            "port" to 22,
            "directories" to listOf("outbox", "processed"),
            "userName" to "feed_user",
            "password" to "super-secret-pass",
            "direction" to "INBOUND",
            "filters" to listOf("SEPA_CREDIT_*", "DAILY_REPORT_*"),
        ),
        "updatedBy" to "p1-dev-group",
    )

    private fun ftpRequest(description: String = "FTP OUTBOUND Feed") = mapOf(
        "type" to "FTP",
        "userId" to "p2-dev-group",
        "shortDescription" to description,
        "isEnabled" to false,
        "details" to mapOf(
            "host" to "ftp.internal.bank.com",
            "port" to 21,
            "directories" to listOf("/reports"),
            "userName" to "ftp_user",
            "password" to "another-secret",
            "direction" to "INBOUND",
            "filters" to listOf("REPORT_*.csv"),
        ),
        "updatedBy" to "p2-dev-group",
    )

    private fun s3Request(description: String = "AWS S3 Feed") = mapOf(
        "type" to "S3",
        "userId" to "p3-dev-group",
        "shortDescription" to description,
        "isEnabled" to false,
        "details" to mapOf(
            "bucketName" to "my-sepa-feeds",
            "region" to "eu-west-1",
            "accessKeyId" to "AKIAIOSFODNN7EXAMPLE",
            "secretAccessKey" to "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            "prefix" to "inbound/sepa/",
            "direction" to "INBOUND",
            "filters" to listOf("SEPA_*.xml"),
        ),
        "updatedBy" to "p3-dev-group",
    )

    @BeforeEach
    fun cleanDb() {
        downloadedFileRepo.deleteAll()
        integrationRepo.deleteAll()
    }

    // ── POST /api/v1/integrations ──────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/integrations")
    inner class CreateIntegration {

        @Test
        @DisplayName("creates SFTP integration and returns 201 with sanitized response")
        fun `create SFTP - returns 201 with masked password`() {
            val response = restTemplate.postForEntity(
                "/api/v1/integrations",
                sftpRequest(),
                Map::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
            val body = response.body!!
            assertThat(body["type"]).isEqualTo("SFTP")
            assertThat(body["userId"]).isEqualTo("p1-dev-group")
            assertThat(body["shortDescription"]).isEqualTo("SEPA INBOUND SFTP")
            assertThat(body["isEnabled"]).isEqualTo(false)

            // CRITICAL: password must NEVER appear in responses
            @Suppress("UNCHECKED_CAST")
            val details = body["details"] as Map<String, Any>
            assertThat(details["password"]).isEqualTo("***")
            assertThat(details["userName"]).isEqualTo("feed_user")
            assertThat(details["host"]).isEqualTo("sftp.bank.com")

            // Verify persisted in DB
            val saved = integrationRepo.findAll()
            assertThat(saved).hasSize(1)
            assertThat(saved[0].type.name).isEqualTo("SFTP")
            // Encrypted details must NEVER be the plain password
            assertThat(saved[0].encryptedDetails).doesNotContain("super-secret-pass")
        }

        @Test
        @DisplayName("creates FTP integration and returns 201")
        fun `create FTP - persisted and returned`() {
            val response = restTemplate.postForEntity(
                "/api/v1/integrations",
                ftpRequest(),
                Map::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
            val body = response.body!!
            assertThat(body["type"]).isEqualTo("FTP")

            @Suppress("UNCHECKED_CAST")
            val details = body["details"] as Map<String, Any>
            assertThat(details["password"]).isEqualTo("***")
        }

        @Test
        @DisplayName("creates S3 integration with masked secret key")
        fun `create S3 - secret key masked as stars`() {
            val response = restTemplate.postForEntity(
                "/api/v1/integrations",
                s3Request(),
                Map::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)

            @Suppress("UNCHECKED_CAST")
            val details = (response.body ?: error("no body"))["details"] as Map<String, Any>
            assertThat(details["secretAccessKey"]).isEqualTo("***")
            // Access key ID is partially masked (first 4 chars kept)
            assertThat(details["accessKeyId"].toString()).endsWith("****")
        }

        @Test
        @DisplayName("creates integration with isEnabled=true and activates route")
        fun `create with isEnabled=true - activateRoute called`() {
            restTemplate.postForEntity(
                "/api/v1/integrations",
                sftpRequest(enabled = true),
                Map::class.java,
            )

            // DynamicRouteManager.activateRoute should have been called
            verify(exactly = 1) { dynamicRouteManager.activateRoute(any()) }

            val saved = integrationRepo.findAll()
            assertThat(saved).hasSize(1)
            assertThat(saved[0].isEnabled).isTrue()
        }

        @Test
        @DisplayName("returns 400 for unknown integration type")
        fun `create with invalid type - 400`() {
            val response = restTemplate.postForEntity(
                "/api/v1/integrations",
                mapOf(
                    "type" to "SFTP_INVALID",
                    "userId" to "u",
                    "shortDescription" to "s",
                    "details" to emptyMap<String, Any>(),
                    "updatedBy" to "u",
                ),
                Map::class.java,
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }

        @Test
        @DisplayName("returns 400 when required SFTP fields are missing")
        fun `create SFTP without required host - 400`() {
            val response = restTemplate.postForEntity(
                "/api/v1/integrations",
                mapOf(
                    "type" to "SFTP",
                    "userId" to "u",
                    "shortDescription" to "s",
                    "details" to mapOf("port" to 22), // missing host, userName, password
                    "updatedBy" to "u",
                ),
                Map::class.java,
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }

        @Test
        @DisplayName("normalises comma-separated filter list")
        fun `create with comma-separated filters - normalised correctly`() {
            val req = mapOf(
                "type" to "SFTP",
                "userId" to "u",
                "shortDescription" to "Filter Test",
                "isEnabled" to false,
                "details" to mapOf(
                    "host" to "h",
                    "port" to 22,
                    "directories" to listOf("/in"),
                    "userName" to "u",
                    "password" to "p",
                    "direction" to "INBOUND",
                    // Single-string comma-separated — should be split into three entries
                    "filters" to listOf("FILE_A, FILE_B, FILE_C"),
                ),
                "updatedBy" to "u",
            )
            val response = restTemplate.postForEntity("/api/v1/integrations", req, Map::class.java)
            assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)

            // Retrieve and decrypt to confirm filters were split
            val id = (response.body ?: error("no body"))["id"].toString()
            val getResponse = restTemplate.getForEntity("/api/v1/integrations/$id", Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val filters = (getResponse.body!!["details"] as Map<String, Any>)["filters"] as List<*>
            assertThat(filters).containsExactlyInAnyOrder("FILE_A", "FILE_B", "FILE_C")
        }
    }

    // ── GET /api/v1/integrations ───────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/integrations")
    inner class ListIntegrations {

        @Test
        @DisplayName("returns empty list when no integrations exist")
        fun `list returns empty`() {
            val response = restTemplate.getForEntity("/api/v1/integrations", List::class.java)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).isEmpty()
        }

        @Test
        @DisplayName("returns all integrations with sanitized details")
        fun `list returns all with masked secrets`() {
            // Create 3 different integration types
            restTemplate.postForEntity("/api/v1/integrations", sftpRequest("SFTP Feed"), Map::class.java)
            restTemplate.postForEntity("/api/v1/integrations", ftpRequest("FTP Feed"), Map::class.java)
            restTemplate.postForEntity("/api/v1/integrations", s3Request("S3 Feed"), Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val response = restTemplate.getForEntity(
                "/api/v1/integrations",
                List::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).hasSize(3)

            // Verify no password leaks in list response
            @Suppress("UNCHECKED_CAST")
            (response.body as List<Map<String, Any>>).forEach { integration ->
                val details = integration["details"] as Map<String, Any>
                when (integration["type"]) {
                    "SFTP", "FTP" -> assertThat(details["password"]).isEqualTo("***")
                    "S3" -> assertThat(details["secretAccessKey"]).isEqualTo("***")
                }
            }
        }
    }

    // ── GET /api/v1/integrations/{id} ─────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/integrations/{id}")
    inner class GetIntegration {

        @Test
        @DisplayName("returns 200 with integration details")
        fun `get by id returns 200`() {
            val createResponse = restTemplate.postForEntity(
                "/api/v1/integrations",
                sftpRequest(),
                Map::class.java,
            )
            val id = createResponse.body!!["id"].toString()

            val getResponse = restTemplate.getForEntity(
                "/api/v1/integrations/$id",
                Map::class.java,
            )
            assertThat(getResponse.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(getResponse.body!!["id"]).isEqualTo(id)
            assertThat(getResponse.body!!["type"]).isEqualTo("SFTP")
        }

        @Test
        @DisplayName("returns 404 for unknown id")
        fun `get by unknown id returns 404`() {
            val response = restTemplate.getForEntity(
                "/api/v1/integrations/non-existent-id",
                Map::class.java,
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    // ── PUT /api/v1/integrations/{id} ─────────────────────────────────────────

    @Nested
    @DisplayName("PUT /api/v1/integrations/{id}")
    inner class UpdateIntegration {

        @Test
        @DisplayName("updates description and returns updated entity")
        fun `update description - returns 200`() {
            val id = createSftp()

            val updateRequest = mapOf(
                "shortDescription" to "Updated SEPA INBOUND SFTP",
                "updatedBy" to "admin",
            )

            val updateResponse = restTemplate.exchange(
                "/api/v1/integrations/$id",
                HttpMethod.PUT,
                HttpEntity(updateRequest),
                Map::class.java,
            )

            assertThat(updateResponse.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(updateResponse.body!!["shortDescription"]).isEqualTo("Updated SEPA INBOUND SFTP")
        }

        @Test
        @DisplayName("updates connection details and re-encrypts them")
        fun `update details - re-encrypted in DB`() {
            val id = createSftp()
            val originalEncrypted = integrationRepo.findById(id).get().encryptedDetails

            val updateRequest = mapOf(
                "details" to mapOf(
                    "host" to "new-sftp.bank.com",
                    "port" to 2222,
                    "directories" to listOf("/new-outbox"),
                    "userName" to "new_user",
                    "password" to "brand-new-password",
                    "direction" to "INBOUND",
                    "filters" to listOf("NEW_*"),
                ),
                "updatedBy" to "admin",
            )

            restTemplate.exchange(
                "/api/v1/integrations/$id",
                HttpMethod.PUT,
                HttpEntity(updateRequest),
                Map::class.java,
            )

            val newEncrypted = integrationRepo.findById(id).get().encryptedDetails
            // Encrypted blob should change after details update
            assertThat(newEncrypted).isNotEqualTo(originalEncrypted)
            // New encrypted blob must not contain the new password in plain text
            assertThat(newEncrypted).doesNotContain("brand-new-password")

            // reloadRoute should be called since details changed
            verify(atLeast = 1) { dynamicRouteManager.reloadRoute(any()) }
        }

        @Test
        @DisplayName("returns 404 for unknown id")
        fun `update unknown id returns 404`() {
            val response = restTemplate.exchange(
                "/api/v1/integrations/unknown",
                HttpMethod.PUT,
                HttpEntity(mapOf("updatedBy" to "admin")),
                Map::class.java,
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    // ── POST /api/v1/integrations/{id}/enable ─────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/integrations/{id}/enable")
    inner class EnableIntegration {

        @Test
        @DisplayName("enables a disabled integration and starts route")
        fun `enable - isEnabled becomes true and activateRoute called`() {
            val id = createSftp(enabled = false)

            val response = restTemplate.postForEntity(
                "/api/v1/integrations/$id/enable?updatedBy=admin",
                null,
                Map::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!["isEnabled"]).isEqualTo(true)

            // Verify DB state
            assertThat(integrationRepo.findById(id).get().isEnabled).isTrue()

            // Route should be activated
            verify(atLeast = 1) { dynamicRouteManager.activateRoute(any()) }
        }

        @Test
        @DisplayName("returns 400 when integration is already enabled")
        fun `enable already-enabled integration - 400`() {
            val id = createSftp(enabled = true)

            val response = restTemplate.postForEntity(
                "/api/v1/integrations/$id/enable?updatedBy=admin",
                null,
                Map::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    // ── POST /api/v1/integrations/{id}/disable ────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/integrations/{id}/disable")
    inner class DisableIntegration {

        @Test
        @DisplayName("disables an enabled integration and suspends route")
        fun `disable - isEnabled becomes false and deactivateRoute called`() {
            val id = createSftp(enabled = true)

            val response = restTemplate.postForEntity(
                "/api/v1/integrations/$id/disable?updatedBy=admin",
                null,
                Map::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!["isEnabled"]).isEqualTo(false)

            assertThat(integrationRepo.findById(id).get().isEnabled).isFalse()
            verify(atLeast = 1) { dynamicRouteManager.deactivateRoute(any()) }
        }

        @Test
        @DisplayName("returns 400 when integration is already disabled")
        fun `disable already-disabled integration - 400`() {
            val id = createSftp(enabled = false)

            val response = restTemplate.postForEntity(
                "/api/v1/integrations/$id/disable?updatedBy=admin",
                null,
                Map::class.java,
            )

            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    // ── DELETE /api/v1/integrations/{id} ──────────────────────────────────────

    @Nested
    @DisplayName("DELETE /api/v1/integrations/{id}")
    inner class DeleteIntegration {

        @Test
        @DisplayName("deletes integration, removes from DB, and stops route")
        fun `delete - 204 and entity removed from DB`() {
            val id = createSftp()

            val deleteResponse = restTemplate.exchange(
                "/api/v1/integrations/$id",
                HttpMethod.DELETE,
                null,
                Void::class.java,
            )
            assertThat(deleteResponse.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

            // Entity gone from DB
            assertThat(integrationRepo.existsById(id)).isFalse()

            // Route removal requested
            verify(exactly = 1) { dynamicRouteManager.removeRoute(id) }

            // Subsequent GET should return 404
            val getResponse = restTemplate.getForEntity(
                "/api/v1/integrations/$id",
                Map::class.java,
            )
            assertThat(getResponse.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }

        @Test
        @DisplayName("returns 404 for unknown id")
        fun `delete unknown id - 404`() {
            val response = restTemplate.exchange(
                "/api/v1/integrations/unknown",
                HttpMethod.DELETE,
                null,
                Void::class.java,
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }

    // ── Encryption round-trip ─────────────────────────────────────────────────

    @Nested
    @DisplayName("Encryption round-trip")
    inner class EncryptionRoundTrip {

        @Test
        @DisplayName("credentials survive encrypt-persist-decrypt cycle intact")
        fun `SFTP details survive full encryption round-trip`() {
            val createResponse = restTemplate.postForEntity(
                "/api/v1/integrations",
                mapOf(
                    "type" to "SFTP",
                    "userId" to "enc-test",
                    "shortDescription" to "Encryption Test",
                    "isEnabled" to false,
                    "details" to mapOf(
                        "host" to "sftp.test.com",
                        "port" to 2222,
                        "directories" to listOf("/secure"),
                        "userName" to "secure_user",
                        "password" to "TopSecret!@#\$", // special chars
                        "direction" to "INBOUND",
                        "filters" to emptyList<String>(),
                    ),
                    "updatedBy" to "enc-test",
                ),
                Map::class.java,
            )
            assertThat(createResponse.statusCode).isEqualTo(HttpStatus.CREATED)
            val id = createResponse.body!!["id"].toString()

            // DB: must be encrypted (not contain the password)
            val entity = integrationRepo.findById(id).get()
            assertThat(entity.encryptedDetails).doesNotContain("TopSecret")
            assertThat(entity.encryptedDetails).doesNotContain("secure_user")

            // GET: decrypts and sanitises — password masked, userName visible
            val getResponse = restTemplate.getForEntity("/api/v1/integrations/$id", Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val details = getResponse.body!!["details"] as Map<String, Any>
            assertThat(details["userName"]).isEqualTo("secure_user")
            assertThat(details["password"]).isEqualTo("***") // sanitized
            assertThat(details["host"]).isEqualTo("sftp.test.com")
            assertThat((details["directories"] as List<*>)).containsExactly("/secure")
        }

        @Test
        @DisplayName("two identical integrations produce different ciphertext (per-row salt)")
        fun `different ciphertext for identical details (per-row salt)`() {
            val req = sftpRequest("Same Credentials One")
            restTemplate.postForEntity("/api/v1/integrations", req, Map::class.java)
            restTemplate.postForEntity(
                "/api/v1/integrations",
                req.toMutableMap().apply { put("shortDescription", "Same Credentials Two") },
                Map::class.java,
            )

            val all = integrationRepo.findAll()
            assertThat(all).hasSize(2)
            // Different UUIDs as salt → different ciphertexts even for same payload
            assertThat(all[0].encryptedDetails).isNotEqualTo(all[1].encryptedDetails)
        }
    }

    // ── Full lifecycle ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Full lifecycle")
    inner class FullLifecycle {

        @Test
        @DisplayName("complete lifecycle: create → enable → disable → update → delete")
        fun `full lifecycle of an SFTP integration`() {
            // 1. Create
            val id = createSftp("Lifecycle Test")
            assertThat(integrationRepo.findById(id).get().isEnabled).isFalse()

            // 2. Enable
            val enableResp = restTemplate.postForEntity(
                "/api/v1/integrations/$id/enable?updatedBy=ops",
                null,
                Map::class.java,
            )
            assertThat(enableResp.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(enableResp.body!!["isEnabled"]).isEqualTo(true)

            // 3. Disable
            val disableResp = restTemplate.postForEntity(
                "/api/v1/integrations/$id/disable?updatedBy=ops",
                null,
                Map::class.java,
            )
            assertThat(disableResp.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(disableResp.body!!["isEnabled"]).isEqualTo(false)

            // 4. Update description
            val updateResp = restTemplate.exchange(
                "/api/v1/integrations/$id",
                HttpMethod.PUT,
                HttpEntity(mapOf("shortDescription" to "Updated Lifecycle Test", "updatedBy" to "ops")),
                Map::class.java,
            )
            assertThat(updateResp.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(updateResp.body!!["shortDescription"]).isEqualTo("Updated Lifecycle Test")

            // 5. Delete
            val deleteResp = restTemplate.exchange(
                "/api/v1/integrations/$id",
                HttpMethod.DELETE,
                null,
                Void::class.java,
            )
            assertThat(deleteResp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
            assertThat(integrationRepo.existsById(id)).isFalse()
        }
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private fun createSftp(description: String = "SEPA INBOUND SFTP", enabled: Boolean = false): String {
        val response = restTemplate.postForEntity(
            "/api/v1/integrations",
            sftpRequest(description, enabled),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        return response.body!!["id"].toString()
    }
}
