package com.transformplatform.integration.camel

import com.transformplatform.integration.camel.FileDownloadProcessor.Companion.IDEMPOTENCY_PASSED
import com.transformplatform.integration.camel.FileDownloadProcessor.Companion.INTEGRATION_ID_HEADER
import com.transformplatform.integration.model.FtpDetails
import com.transformplatform.integration.model.IntegrationDetails
import com.transformplatform.integration.model.ServiceIntegration
import com.transformplatform.integration.model.SftpDetails
import mu.KotlinLogging
import org.apache.camel.LoggingLevel
import org.apache.camel.builder.RouteBuilder

private val log = KotlinLogging.logger {}

/**
 * Camel [RouteBuilder] for SFTP and FTP polling routes.
 *
 * One instance is created per [ServiceIntegration] by [DynamicRouteManager].
 * When an integration has multiple [SftpDetails.directories], a sub-route is
 * created for each directory.  All sub-routes share the same [FileDownloadProcessor].
 *
 * **Route topology:**
 * ```
 * sftp://host:port/dir?options
 *   → setHeader(IntegrationId)
 *   → processor: checkIdempotency     ← DB lookup
 *   → filter(IdempotencyPassed==true)
 *       → processor: archiveAndRecord  ← upload to MinIO + insert DB row
 *       → log INFO
 * ```
 *
 * **Post-processing:**  On success Camel moves the remote file to `.archived/`
 * on the source server (`move=.archived/${file:name}`), preventing re-download
 * on subsequent polls.  Set `archiveOnSource=false` in [SftpDetails] to disable.
 *
 * **Error handling:**  Dead Letter Channel with 3 retries / 5 s delay.
 * Exhausted messages are logged at ERROR level and the DLQ route is a no-op
 * (operators investigate via Kibana / Jaeger).
 */
class SftpFtpRouteBuilder(
    private val integration: ServiceIntegration,
    private val details: IntegrationDetails, // SftpDetails or FtpDetails
    private val processor: FileDownloadProcessor,
) : RouteBuilder() {

    override fun configure() {
        // ── Dead letter channel ───────────────────────────────────────────────
        errorHandler(
            deadLetterChannel("direct:dlq-${integration.id}")
                .maximumRedeliveries(3)
                .redeliveryDelay(5_000)
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .logHandled(true)
                .logExhaustedMessageHistory(false),
        )

        from("direct:dlq-${integration.id}")
            .routeId("dlq-${integration.id}")
            .log(
                LoggingLevel.ERROR,
                "FileDownloadDLQ",
                "Download failed (max retries) — integration=${integration.id} " +
                    "file=\${header.CamelFileName} error=\${exception.message}",
            )

        // ── One polling route per directory ───────────────────────────────────
        val directories = when (details) {
            is SftpDetails -> details.directories.ifEmpty { listOf("/") }
            is FtpDetails -> details.directories.ifEmpty { listOf("/") }
            else -> throw IllegalArgumentException("SftpFtpRouteBuilder used with non-SFTP/FTP type")
        }

        directories.forEachIndexed { idx, directory ->
            val routeId = if (directories.size == 1) {
                integration.camelRouteId()
            } else {
                "${integration.camelRouteId()}-dir$idx"
            }

            val fromUri = buildUri(directory)
            log.info("Building route [$routeId]: $fromUri")

            buildDownloadRoute(routeId, fromUri)
        }
    }

    // ── Route definition ──────────────────────────────────────────────────────

    private fun buildDownloadRoute(routeId: String, fromUri: String) {
        from(fromUri)
            .routeId(routeId)
            .setHeader(INTEGRATION_ID_HEADER, constant(integration.id))
            // Step 1: idempotency check
            .process { exchange -> processor.checkIdempotency(exchange) }
            // Step 2: archive to S3 + record in DB (only if new file)
            .filter(simple("\${header.$IDEMPOTENCY_PASSED} == true"))
            .process { exchange -> processor.archiveAndRecord(exchange) }
            .log(
                LoggingLevel.INFO,
                "FileDownload",
                "Archived — integration=${integration.id} " +
                    "file=\${header.CamelFileName} " +
                    "size=\${header.CamelFileLength}",
            )
            .end()
    }

    // ── URI builders ──────────────────────────────────────────────────────────

    private fun buildUri(directory: String): String = when (details) {
        is SftpDetails -> buildSftpUri(details, directory)
        is FtpDetails -> buildFtpUri(details, directory)
        else -> throw IllegalArgumentException("Unsupported details type: ${details::class.simpleName}")
    }

    private fun buildSftpUri(d: SftpDetails, directory: String): String = buildString {
        // Ensure directory starts with /
        val dir = if (directory.startsWith("/")) directory else "/$directory"
        append("sftp://${d.host}:${d.port}$dir")
        append("?username=${d.userName}")
        append("&password=RAW(${d.password})") // RAW(...) prevents URI encoding issues
        appendCommonOptions(d.filters)
        // Keep connection alive across poll cycles
        append("&soTimeout=30000")
        append("&connectTimeout=10000")
    }

    private fun buildFtpUri(d: FtpDetails, directory: String): String = buildString {
        val dir = if (directory.startsWith("/")) directory else "/$directory"
        append("ftp://${d.userName}@${d.host}:${d.port}$dir")
        append("?password=RAW(${d.password})")
        appendCommonOptions(d.filters)
        append("&passiveMode=true")
    }

    private fun StringBuilder.appendCommonOptions(filters: List<String>) {
        append("&delay=60000")
        append("&maxMessagesPerPoll=20")
        append("&shuffle=false")
        // Ant-style glob filter — blank means accept all
        val include = buildAntInclude(filters)
        if (include.isNotBlank()) append("&antInclude=$include")
        // Move remote file to .archived/ folder after successful download
        append("&noop=false")
        append("&move=.archived/\${file:name}")
        // Stream large files to avoid OOM
        append("&streamDownload=true")
        // Temp dir for in-progress downloads
        append("&localWorkDirectory=/tmp/camel-sftp-work/${integration.id}")
        // Don't throw if the remote dir doesn't exist yet — silently skip
        append("&throwExceptionOnConnectFailed=false")
        append("&disconnect=false")
        append("&binary=true")
    }

    /**
     * Converts filter list to Ant include expression.
     * A filter like "SEPA_CREDIT_*" matches any file whose name starts with "SEPA_CREDIT_".
     * Filters that already contain `*` or `?` are used as-is.
     */
    private fun buildAntInclude(filters: List<String>): String {
        if (filters.isEmpty()) return ""
        return filters.joinToString(",") { f ->
            if (f.contains('*') || f.contains('?')) f else "$f*"
        }
    }
}
