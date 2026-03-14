package com.transformplatform.integration.camel

import com.transformplatform.integration.camel.FileDownloadProcessor.Companion.IDEMPOTENCY_PASSED
import com.transformplatform.integration.camel.FileDownloadProcessor.Companion.INTEGRATION_ID_HEADER
import com.transformplatform.integration.model.S3Details
import com.transformplatform.integration.model.ServiceIntegration
import mu.KotlinLogging
import org.apache.camel.LoggingLevel
import org.apache.camel.builder.RouteBuilder

private val log = KotlinLogging.logger {}

/**
 * S3RouteManager — Camel [RouteBuilder] that polls an **external** AWS S3 (or
 * S3-compatible) bucket and archives discovered files into the internal MinIO
 * backup store via [FileDownloadProcessor.archiveAndRecord].
 *
 * Created by [DynamicRouteManager] for every [ServiceIntegration] with
 * [IntegrationType.S3].
 *
 * **Route topology:**
 * ```
 * aws2-s3://bucket?options
 *   → setHeader(IntegrationId)
 *   → processor: checkIdempotency
 *   → filter(IdempotencyPassed==true)
 *       → processor: archiveAndRecord   ← upload to internal MinIO
 *       → log INFO
 * ```
 *
 * **Archival on source:**  After a successful read, Camel moves the object to a
 * `{bucket}-archived` bucket (`moveAfterRead=true`).  This prevents re-downloading
 * while preserving the original file for audit purposes.
 *
 * **Credentials:**  Passed as URI parameters.  For production workloads consider
 * switching to instance-profile / IRSA by omitting `accessKey`/`secretKey` and
 * setting `useDefaultCredentialsProvider=true`.
 */
class S3PollingRouteBuilder(
    private val integration: ServiceIntegration,
    private val details: S3Details,
    private val processor: FileDownloadProcessor,
) : RouteBuilder() {

    override fun configure() {
        // ── Dead letter channel ───────────────────────────────────────────────
        errorHandler(
            deadLetterChannel("direct:s3-dlq-${integration.id}")
                .maximumRedeliveries(3)
                .redeliveryDelay(10_000)
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .logHandled(true),
        )

        from("direct:s3-dlq-${integration.id}")
            .routeId("s3-dlq-${integration.id}")
            .log(
                LoggingLevel.ERROR,
                "S3DownloadDLQ",
                "S3 download failed (max retries) — integration=${integration.id} " +
                    "key=\${header.CamelAwsS3Key} error=\${exception.message}",
            )

        // ── Main S3 polling route ─────────────────────────────────────────────
        val fromUri = buildS3Uri()
        log.info("Building S3 route [${integration.camelRouteId()}]: bucket=${details.bucketName}")

        from(fromUri)
            .routeId(integration.camelRouteId())
            .setHeader(INTEGRATION_ID_HEADER, constant(integration.id))
            // Map S3 object key to the CamelFileName header so FileDownloadProcessor
            // can use the same header regardless of connector type
            .setHeader("CamelFileName", simple("\${header.CamelAwsS3Key}"))
            .setHeader("CamelFilePath", simple("\${header.CamelAwsS3BucketName}/\${header.CamelAwsS3Key}"))
            .setHeader("CamelFileLength", simple("\${header.CamelAwsS3ContentLength}"))
            // Step 1: idempotency check
            .process { exchange -> processor.checkIdempotency(exchange) }
            // Step 2: archive + record (only for new objects)
            .filter(simple("\${header.$IDEMPOTENCY_PASSED} == true"))
            .process { exchange -> processor.archiveAndRecord(exchange) }
            .log(
                LoggingLevel.INFO,
                "S3Download",
                "Archived from S3 — integration=${integration.id} " +
                    "key=\${header.CamelAwsS3Key}",
            )
            .end()
    }

    // ── URI builder ───────────────────────────────────────────────────────────

    private fun buildS3Uri(): String = buildString {
        append("aws2-s3://${details.bucketName}")
        append("?accessKey=RAW(${details.accessKeyId})")
        append("&secretKey=RAW(${details.secretAccessKey})")
        append("&region=${details.region}")
        // Poll prefix (folder) if specified
        details.prefix?.takeIf { it.isNotBlank() }?.let { append("&prefix=$it") }
        // Polling interval
        append("&delay=60000")
        append("&maxMessagesPerPoll=20")
        // Move object to archive bucket after successful consumption
        append("&deleteAfterRead=false")
        append("&moveAfterRead=true")
        append("&destinationBucket=${details.bucketName}-archived")
        // File name filter (comma-separated patterns)
        val fileFilter = buildFileNameFilter(details.filters)
        if (fileFilter.isNotBlank()) append("&fileName=$fileFilter")
        // Auto-create archive destination bucket if it doesn't exist
        append("&autoCreateBucket=true")
        // Stream body rather than loading the whole object into memory
        append("&includeBody=true")
    }

    private fun buildFileNameFilter(filters: List<String>): String {
        if (filters.isEmpty()) return ""
        // aws2-s3 `fileName` option accepts Ant patterns (comma-separated)
        return filters.joinToString(",") { f ->
            if (f.contains('*') || f.contains('?')) f else "$f*"
        }
    }
}
