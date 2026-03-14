package com.transformplatform.integration.service

import com.transformplatform.integration.camel.DynamicRouteManager
import com.transformplatform.integration.model.Direction
import com.transformplatform.integration.model.FtpDetails
import com.transformplatform.integration.model.IntegrationType
import com.transformplatform.integration.model.ServiceIntegration
import com.transformplatform.integration.model.SftpDetails
import com.transformplatform.integration.repository.ServiceIntegrationRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional

/**
 * Unit tests for [ServiceIntegrationService].
 *
 * All dependencies are mocked with MockK so these run without a Spring context,
 * database, or Camel — fast and focused on business logic.
 */
class ServiceIntegrationServiceTest : DescribeSpec({

    // ── Test fixtures ─────────────────────────────────────────────────────────

    val repo = mockk<ServiceIntegrationRepository>(relaxed = true)
    val encryptionSvc = mockk<IntegrationEncryptionService>(relaxed = true)
    val routeManager = mockk<DynamicRouteManager>(relaxed = true)

    val service = ServiceIntegrationService(repo, encryptionSvc, routeManager)

    val sftpDetails = SftpDetails(
        host = "sftp.bank.com",
        port = 22,
        directories = listOf("outbox"),
        userName = "feed_user",
        password = "s3cr3t",
        direction = Direction.INBOUND,
        filters = listOf("SEPA_CREDIT_*", "DAILY_REPORT_*"),
    )

    beforeEach {
        clearMocks(repo, encryptionSvc, routeManager)
    }

    // ── create ────────────────────────────────────────────────────────────────

    describe("create") {

        it("saves the integration and starts a route when isEnabled=true") {
            val savedSlot = mutableListOf<ServiceIntegration>()
            every { repo.save(capture(savedSlot)) } answers {
                val si = firstArg<ServiceIntegration>()
                // Simulate DB assigning the ID (it's already set in the entity, just return it)
                si
            }
            every { encryptionSvc.encryptDetails(any(), any()) } returns "encrypted_blob"
            every { routeManager.activateRoute(any()) } just Runs

            val result = service.create(
                type = IntegrationType.SFTP,
                userId = "p1-dev-group",
                shortDescription = "SEPA Inbound SFTP",
                isEnabled = true,
                details = sftpDetails,
                updatedBy = "p1-dev-group",
            )

            result.type shouldBe IntegrationType.SFTP
            result.userId shouldBe "p1-dev-group"
            result.isEnabled shouldBe true

            verify(exactly = 1) { routeManager.activateRoute(any()) }
        }

        it("does NOT start a route when isEnabled=false") {
            every { repo.save(any()) } answers { firstArg() }
            every { encryptionSvc.encryptDetails(any(), any()) } returns "enc"

            service.create(
                type = IntegrationType.FTP,
                userId = "user-x",
                shortDescription = "FTP feed",
                isEnabled = false,
                details = FtpDetails("ftp.host", 21, listOf("/in"), "u", "p"),
                updatedBy = "user-x",
            )

            verify(exactly = 0) { routeManager.activateRoute(any()) }
        }
    }

    // ── enable / disable ──────────────────────────────────────────────────────

    describe("enable") {
        it("enables a disabled integration and activates the route") {
            val integration = ServiceIntegration(
                type = IntegrationType.SFTP,
                userId = "u",
                shortDescription = "s",
                isEnabled = false,
                encryptedDetails = "enc",
                updatedBy = "u",
            )
            every { repo.findById(integration.id) } returns Optional.of(integration)
            every { repo.save(any()) } answers { firstArg() }
            every { routeManager.activateRoute(any()) } just Runs

            val result = service.enable(integration.id, "admin")

            result.isEnabled shouldBe true
            verify(exactly = 1) { routeManager.activateRoute(any()) }
        }

        it("throws when integration is already enabled") {
            val integration = ServiceIntegration(
                type = IntegrationType.SFTP,
                userId = "u",
                shortDescription = "s",
                isEnabled = true,
                encryptedDetails = "enc",
                updatedBy = "u",
            )
            every { repo.findById(integration.id) } returns Optional.of(integration)

            shouldThrow<IllegalStateException> { service.enable(integration.id, "admin") }
        }
    }

    describe("disable") {
        it("disables an enabled integration and suspends the route") {
            val integration = ServiceIntegration(
                type = IntegrationType.SFTP,
                userId = "u",
                shortDescription = "s",
                isEnabled = true,
                encryptedDetails = "enc",
                updatedBy = "u",
            )
            every { repo.findById(integration.id) } returns Optional.of(integration)
            every { repo.save(any()) } answers { firstArg() }
            every { routeManager.deactivateRoute(any()) } just Runs

            val result = service.disable(integration.id, "admin")

            result.isEnabled shouldBe false
            verify(exactly = 1) { routeManager.deactivateRoute(any()) }
        }
    }

    // ── delete ────────────────────────────────────────────────────────────────

    describe("delete") {
        it("removes the route and deletes the entity") {
            val integration = ServiceIntegration(
                type = IntegrationType.S3,
                userId = "u",
                shortDescription = "s",
                isEnabled = true,
                encryptedDetails = "enc",
                updatedBy = "u",
            )
            every { repo.findById(integration.id) } returns Optional.of(integration)
            every { repo.delete(any()) } just Runs
            every { routeManager.removeRoute(any()) } just Runs

            service.delete(integration.id)

            verify(exactly = 1) { routeManager.removeRoute(integration.id) }
            verify(exactly = 1) { repo.delete(integration) }
        }
    }

    // ── normaliseFilters ──────────────────────────────────────────────────────

    describe("normaliseFilters") {
        it("flattens a comma-separated single-string list") {
            val result = ServiceIntegrationService.normaliseFilters(
                listOf("FILE_A, FILE_B, FILE_C"),
            )
            result shouldContainExactlyInAnyOrder listOf("FILE_A", "FILE_B", "FILE_C")
        }

        it("handles a proper list unchanged") {
            val result = ServiceIntegrationService.normaliseFilters(
                listOf("SEPA_*", "DAILY_*"),
            )
            result shouldContainExactlyInAnyOrder listOf("SEPA_*", "DAILY_*")
        }

        it("trims whitespace") {
            val result = ServiceIntegrationService.normaliseFilters(listOf("  A  ,  B  "))
            result shouldContainExactlyInAnyOrder listOf("A", "B")
        }

        it("returns empty list for empty input") {
            ServiceIntegrationService.normaliseFilters(emptyList()) shouldBe emptyList()
        }
    }
})
