package com.transformplatform.integration.validation

import com.transformplatform.integration.model.IntegrationType
import com.transformplatform.integration.model.ServiceIntegration
import com.transformplatform.integration.repository.DownloadedFileRepository
import com.transformplatform.integration.repository.ServiceIntegrationRepository
import com.transformplatform.integration.storage.S3ArchivalService
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk

/**
 * Tests for [StartupValidator].
 *
 * Verifies that the startup check runs without throwing, handles errors
 * gracefully, and logs the correct summary.
 */
class StartupValidatorTest : DescribeSpec({

    val integrationRepo = mockk<ServiceIntegrationRepository>(relaxed = true)
    val downloadedFileRepo = mockk<DownloadedFileRepository>(relaxed = true)
    val s3ArchivalService = mockk<S3ArchivalService>(relaxed = true)

    val validator = StartupValidator(
        integrationRepo,
        downloadedFileRepo,
        s3ArchivalService,
    )

    beforeEach { clearMocks(integrationRepo, downloadedFileRepo, s3ArchivalService) }

    describe("validate") {

        it("completes without throwing even when there are no integrations") {
            every { integrationRepo.count() } returns 0
            every { downloadedFileRepo.countByProcessingStatus(any()) } returns 0
            every { s3ArchivalService.bucketExists() } returns true
            every { integrationRepo.findAll() } returns emptyList()

            // Should not throw
            validator.validate()
        }

        it("completes when MinIO is unreachable (logs error but does not throw)") {
            every { integrationRepo.count() } returns 0
            every { downloadedFileRepo.countByProcessingStatus(any()) } returns 0
            every { s3ArchivalService.bucketExists() } throws RuntimeException("Connection refused")
            every { integrationRepo.findAll() } returns emptyList()

            // Must NOT propagate the exception — validator swallows and logs it
            validator.validate()
        }

        it("logs integration summary for registered integrations") {
            val integrations = listOf(
                ServiceIntegration(
                    type = IntegrationType.SFTP,
                    userId = "p1-dev-group",
                    shortDescription = "SEPA INBOUND",
                    isEnabled = true,
                    encryptedDetails = "enc",
                    updatedBy = "admin",
                ),
                ServiceIntegration(
                    type = IntegrationType.S3,
                    userId = "p2-group",
                    shortDescription = "AWS S3 feed",
                    isEnabled = false,
                    encryptedDetails = "enc",
                    updatedBy = "admin",
                ),
            )
            every { integrationRepo.count() } returns 2
            every { downloadedFileRepo.countByProcessingStatus(any()) } returns 5
            every { s3ArchivalService.bucketExists() } returns true
            every { integrationRepo.findAll() } returns integrations

            validator.validate() // smoke test — no assertions, just verify no crash
        }
    }
})
