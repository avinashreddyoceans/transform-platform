package com.transformplatform.integration.camel

import com.transformplatform.integration.model.DownloadedFile
import com.transformplatform.integration.repository.DownloadedFileRepository
import com.transformplatform.integration.storage.S3ArchivalService
import mu.KotlinLogging
import org.apache.camel.Exchange
import org.springframework.stereotype.Component
import java.io.InputStream

private val log = KotlinLogging.logger {}

/**
 * Shared Camel [Exchange] processor injected into every file-download route.
 *
 * Called in two steps per exchange (set by the route builders via direct method references):
 *
 * **Step 1 — Idempotency check** ([checkIdempotency]):
 *  Looks up `(integrationId, fileName)` in [DownloadedFileRepository].
 *  Sets header [IDEMPOTENCY_PASSED] = `true` if this is a new file, `false` if
 *  already downloaded.  The route's `filter(simple(...))` block uses this header
 *  to decide whether to proceed to Step 2.
 *
 * **Step 2 — Archive & record** ([archiveAndRecord]):
 *  Reads the exchange body as an [InputStream], uploads it to the S3 archival
 *  store (MinIO) via [S3ArchivalService.archive], then inserts a [DownloadedFile]
 *  row with status `PENDING`.  After this step the file is available for pickup
 *  by the transform pipeline.
 */
@Component
class FileDownloadProcessor(
    private val downloadedFileRepo: DownloadedFileRepository,
    private val s3ArchivalService: S3ArchivalService,
) {

    // ── Step 1: Idempotency check ─────────────────────────────────────────────

    fun checkIdempotency(exchange: Exchange) {
        val integrationId = exchange.getIn().getHeader(INTEGRATION_ID_HEADER, String::class.java)
            ?: return exchange.getIn().setHeader(IDEMPOTENCY_PASSED, false).also {
                log.warn { "checkIdempotency: missing IntegrationId header" }
            }

        val fileName = exchange.getIn().getHeader("CamelFileName", String::class.java)
            ?: return exchange.getIn().setHeader(IDEMPOTENCY_PASSED, false).also {
                log.warn { "checkIdempotency: missing CamelFileName header" }
            }

        val alreadyDownloaded = downloadedFileRepo
            .existsByIntegrationIdAndRemoteFileName(integrationId, fileName)

        exchange.getIn().setHeader(IDEMPOTENCY_PASSED, !alreadyDownloaded)

        if (alreadyDownloaded) {
            log.debug { "Idempotency skip: integration=$integrationId, file=$fileName" }
        }
    }

    // ── Step 2: Archive to S3 + record in DB ─────────────────────────────────

    fun archiveAndRecord(exchange: Exchange) {
        val integrationId = exchange.getIn().getHeader(INTEGRATION_ID_HEADER, String::class.java)
        val fileName = exchange.getIn().getHeader("CamelFileName", String::class.java)
        val filePath = exchange.getIn().getHeader("CamelFilePath", String::class.java)
        val fileSize = exchange.getIn().getHeader("CamelFileLength", Long::class.javaObjectType)
        val lastModified = exchange.getIn()
            .getHeader("CamelFileLastModified", java.util.Date::class.java)?.toInstant()

        requireNotNull(integrationId) { "archiveAndRecord: missing IntegrationId header" }
        requireNotNull(fileName) { "archiveAndRecord: missing CamelFileName header" }

        val stream = exchange.getIn().getBody(InputStream::class.java)
            ?: throw IllegalStateException("No body stream available for file: $fileName")

        // Upload to the centralised S3 archival store
        val result = stream.use {
            s3ArchivalService.archive(
                integrationId = integrationId,
                fileName = fileName,
                stream = it,
                sizeHint = fileSize ?: -1L,
            )
        }

        // Record in DB — unique constraint prevents duplicates
        val record = DownloadedFile(
            integrationId = integrationId,
            remoteFileName = fileName,
            remoteFilePath = filePath,
            fileSizeBytes = fileSize,
            remoteLastModified = lastModified,
            md5Checksum = result.etag,
            storageBucket = result.bucket,
            storageKey = result.key,
        )
        downloadedFileRepo.save(record)

        exchange.getIn().setHeader(DOWNLOADED_FILE_ID_HEADER, record.id)
        log.info {
            "File archived: integration=$integrationId, file=$fileName, " +
                "key=${result.key}, size=${fileSize ?: "unknown"}"
        }
    }

    companion object {
        /** Header set/read by Camel route DSL. */
        const val INTEGRATION_ID_HEADER = "IntegrationId"
        const val DOWNLOADED_FILE_ID_HEADER = "DownloadedFileId"

        /**
         * Header set by [checkIdempotency].
         * Routes filter on `"\${header.IdempotencyPassed} == true"`.
         */
        const val IDEMPOTENCY_PASSED = "IdempotencyPassed"
    }
}
