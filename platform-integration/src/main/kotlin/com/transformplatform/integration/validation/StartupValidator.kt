package com.transformplatform.integration.validation

import com.transformplatform.integration.model.IntegrationType
import com.transformplatform.integration.repository.DownloadedFileRepository
import com.transformplatform.integration.repository.ServiceIntegrationRepository
import com.transformplatform.integration.storage.S3ArchivalService
import mu.KotlinLogging
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * Startup health check that runs after the application context is fully started.
 *
 * Validates:
 * 1. **Database connectivity** — counts integrations and downloaded files.
 * 2. **S3 archival store** — checks MinIO bucket reachability.
 * 3. **Integration route summary** — logs the Camel route status for every
 *    registered integration so operators can see the starting state in logs.
 *
 * This component runs at [Order] 1 so it completes before [DynamicRouteManager]
 * (@Order 2) registers Camel routes.  Any MinIO connectivity failure is logged
 * as an ERROR but does NOT prevent the application from starting — the routes
 * will simply fail at download time and retry via the Dead Letter Channel.
 *
 * Check logs for lines beginning with `▶`, `⏸`, `✓`, `✗` for a quick summary.
 */
@Component
@Order(1)
class StartupValidator(
    private val integrationRepo: ServiceIntegrationRepository,
    private val downloadedFileRepo: DownloadedFileRepository,
    private val s3ArchivalService: S3ArchivalService,
) {

    @EventListener(ApplicationReadyEvent::class)
    fun validate() {
        log.info { "╔══ Platform Integration — Startup Validation ══════════════════════════" }
        checkDatabase()
        checkS3ArchivalStore()
        // Route summary runs after DynamicRouteManager has finished registering routes.
        // Schedule a slight delay or use a @PostConstruct on DynamicRouteManager instead.
        // For now, log integration config — route status will show after first poll.
        logIntegrationSummary()
        log.info { "╚══ Startup Validation Complete ════════════════════════════════════════" }
    }

    // ── Checks ────────────────────────────────────────────────────────────────

    private fun checkDatabase() {
        try {
            val integrationCount = integrationRepo.count()
            val pendingFiles = downloadedFileRepo.countByProcessingStatus(
                com.transformplatform.integration.model.FileProcessingStatus.PENDING,
            )
            log.info {
                "  ✓ Database: OK — integrations=$integrationCount, " +
                    "files pending processing=$pendingFiles"
            }
        } catch (e: Exception) {
            log.error(e) { "  ✗ Database: connectivity check failed — ${e.message}" }
        }
    }

    private fun checkS3ArchivalStore() {
        try {
            val exists = s3ArchivalService.bucketExists()
            val status = if (exists) "bucket ready" else "bucket will be created on first upload"
            log.info { "  ✓ S3 Archival Store (MinIO): OK — $status" }
        } catch (e: Exception) {
            log.error(e) {
                "  ✗ S3 Archival Store: connectivity check failed — ${e.message}. " +
                    "File archival will fail until this is resolved."
            }
        }
    }

    private fun logIntegrationSummary() {
        val integrations = try {
            integrationRepo.findAll()
        } catch (e: Exception) {
            return
        }

        if (integrations.isEmpty()) {
            log.info { "  ℹ  No integrations registered. Use POST /api/v1/integrations to onboard." }
            return
        }

        log.info { "  Integration Summary:" }
        log.info { "  ${"─".repeat(70)}" }
        log.info { "  ${"Type".padEnd(6)} ${"Enabled".padEnd(8)} ${"User".padEnd(20)} Description" }
        log.info { "  ${"─".repeat(70)}" }

        integrations.forEach { i ->
            val icon = if (i.isEnabled) "▶" else "⏸"
            val type = i.type.name.padEnd(6)
            val enabled = i.isEnabled.toString().padEnd(8)
            val user = i.userId.take(20).padEnd(20)
            log.info { "  $icon $type $enabled $user ${i.shortDescription}" }
        }

        val byType = integrations.groupBy { it.type }
        log.info { "  ${"─".repeat(70)}" }
        log.info {
            "  Total: ${integrations.size} — " +
                IntegrationType.values().joinToString(", ") { t ->
                    "${t.name}: ${byType[t]?.size ?: 0}"
                }
        }
        log.info {
            "  Enabled: ${integrations.count { it.isEnabled }} / ${integrations.size}"
        }
    }
}
