package com.transformplatform.integration.storage

import com.transformplatform.integration.config.MinioProperties
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.minio.MinioClient
import io.minio.ObjectWriteResponse
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream

/**
 * Unit tests for [S3ArchivalService].
 * The MinIO client is fully mocked — no Docker required.
 */
class S3ArchivalServiceTest : DescribeSpec({

    val minioClient = mockk<MinioClient>(relaxed = true)
    val props = MinioProperties(
        endpoint = "http://localhost:9000",
        accessKey = "minioadmin",
        secretKey = "minioadmin",
        downloadsBucket = "transform-downloads",
    )

    val service = S3ArchivalService(minioClient, props)

    beforeEach { clearMocks(minioClient) }

    // ── buildKey ──────────────────────────────────────────────────────────────

    describe("buildKey") {
        it("produces a path with integrationId, date, and fileName") {
            val key = service.buildKey("integration-abc", "SEPA_CREDIT_20260309.csv")
            key shouldStartWith "integration-abc/"
            key shouldContain "SEPA_CREDIT_20260309.csv"
        }
    }

    // ── archive ───────────────────────────────────────────────────────────────

    describe("archive") {
        it("calls MinIO putObject and returns bucket + key + etag") {
            val mockResponse = mockk<ObjectWriteResponse>()
            every { mockResponse.etag() } returns "\"abc123\""
            every { minioClient.bucketExists(any()) } returns true
            every { minioClient.putObject(any()) } returns mockResponse

            val result = service.archive(
                integrationId = "integ-1",
                fileName = "file.csv",
                stream = ByteArrayInputStream("hello".toByteArray()),
                sizeHint = 5L,
            )

            result.bucket shouldBe "transform-downloads"
            result.key shouldStartWith "integ-1/"
            result.etag shouldBe "abc123" // quotes stripped
        }

        it("creates the bucket if it does not exist") {
            every { minioClient.bucketExists(any()) } returns false
            every { minioClient.makeBucket(any()) } just Runs
            every { minioClient.putObject(any()) } returns mockk<ObjectWriteResponse>(relaxed = true)

            service.archive("id", "f.csv", ByteArrayInputStream(ByteArray(0)))

            verify(exactly = 1) { minioClient.makeBucket(any()) }
        }
    }

    // ── bucketExists ──────────────────────────────────────────────────────────

    describe("bucketExists") {
        it("delegates to MinioClient.bucketExists") {
            every { minioClient.bucketExists(any()) } returns true
            service.bucketExists() shouldBe true

            every { minioClient.bucketExists(any()) } returns false
            service.bucketExists() shouldBe false
        }
    }
})
