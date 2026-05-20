package com.transformplatform.integration.camel

import com.transformplatform.integration.model.IntegrationDetails
import com.transformplatform.integration.model.S3Details
import com.transformplatform.integration.model.ServiceIntegration
import com.transformplatform.integration.repository.ServiceIntegrationRepository
import com.transformplatform.integration.service.IntegrationEncryptionService
import mu.KotlinLogging
import org.apache.camel.CamelContext
import org.apache.camel.builder.RouteBuilder
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

/**
 * Orchestrates all file-download Camel routes across SFTP, FTP, and S3 integrations.
 *
 * **Startup sequence** ([onApplicationReady], fires after [StartupValidator]):
 * 1. Read all integrations from the database.
 * 2. For each integration where [ServiceIntegration.isEnabled] = `true`:
 *    → decrypt details → build a type-specific [RouteBuilder] → `addRoutes()` → route starts.
 * 3. For each disabled integration:
 *    → skip entirely — routes are registered lazily on the first [activateRoute] call.
 *    This avoids a network connection attempt (DNS + TCP) for hosts that are not in use,
 *    which would otherwise surface as a startup error if the host is unreachable.
 *
 * **Runtime control** (called by [ServiceIntegrationService] in response to API calls):
 * - [activateRoute]   — resume a suspended route (or register+start a new one).
 * - [deactivateRoute] — suspend the route without removing it.
 * - [removeRoute]     — stop + remove the route (used on integration delete).
 * - [reloadRoute]     — stop+remove then rebuild (used on integration update).
 *
 * **Delegation:**
 * - SFTP / FTP integrations → [SftpFtpRouteBuilder]
 * - S3 integrations        → [S3PollingRouteBuilder]
 *
 * **Multi-directory integrations:**
 * When an SFTP/FTP integration has more than one directory, [SftpFtpRouteBuilder]
 * creates one sub-route per directory, named `{baseRouteId}-dir0`, `{baseRouteId}-dir1`, …
 * All route-management methods use [routesForIntegration] to find every sub-route by
 * prefix so that multi-directory and single-directory integrations are handled uniformly.
 */
@Component
@Order(2) // After StartupValidator (@Order(1))
class DynamicRouteManager(
    private val camelContext: CamelContext,
    private val integrationRepo: ServiceIntegrationRepository,
    private val encryptionService: IntegrationEncryptionService,
    private val processor: FileDownloadProcessor,
) {

    // ── Startup ───────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent::class)
    @Transactional(readOnly = true)
    fun onApplicationReady() {
        log.info { "DynamicRouteManager — loading routes from database…" }

        val all = integrationRepo.findAll()
        var started = 0
        var skipped = 0
        var failed = 0

        for (integration in all) {
            // Disabled integrations are registered lazily in activateRoute() to avoid
            // attempting a network connection (DNS lookup / TCP handshake) at startup
            // for integrations that are not currently in use.
            if (!integration.isEnabled) {
                log.info { "Integration '${integration.id}' is disabled — deferring route registration until activation" }
                skipped++
                continue
            }
            try {
                val details = encryptionService.decryptDetails(integration)
                registerAndStart(integration, details)
                started++
            } catch (ex: Exception) {
                log.error(ex) { "Failed to register route for integration '${integration.id}'" }
                failed++
            }
        }

        log.info { "DynamicRouteManager — routes ready: started=$started, skipped(disabled)=$skipped, failed=$failed" }
    }

    // ── Runtime control ───────────────────────────────────────────────────────

    /**
     * Start polling for a previously disabled (or brand-new) integration.
     *
     * Resumes all sub-routes if already registered (handles multi-directory integrations),
     * or performs a fresh registration+start if this is the first activation.
     */
    fun activateRoute(integration: ServiceIntegration) {
        val baseRouteId = integration.camelRouteId()
        val existing = routesForIntegration(baseRouteId)
        if (existing.isNotEmpty()) {
            // Resume all sub-routes (handles single-directory and multi-directory integrations)
            existing.forEach { routeId ->
                camelContext.routeController.resumeRoute(routeId)
                log.info { "Route resumed: $routeId" }
            }
        } else {
            val details = encryptionService.decryptDetails(integration)
            registerAndStart(integration, details)
            log.info { "Route registered+started: $baseRouteId" }
        }
    }

    /**
     * Pause polling without removing the route.  Can be resumed instantly.
     *
     * Suspends all sub-routes (handles multi-directory integrations).
     */
    fun deactivateRoute(integration: ServiceIntegration) {
        val baseRouteId = integration.camelRouteId()
        val existing = routesForIntegration(baseRouteId)
        if (existing.isNotEmpty()) {
            existing.forEach { routeId ->
                camelContext.routeController.suspendRoute(routeId)
                log.info { "Route suspended: $routeId" }
            }
        } else {
            log.warn { "deactivateRoute called but no routes registered for: $baseRouteId" }
        }
    }

    /**
     * Fully stop and unregister all routes for the integration.
     * Use when deleting an integration.
     *
     * Removes all polling sub-routes (e.g. `integration-download-{id}-dir0`) and
     * the associated DLQ route (`dlq-{id}`), preventing ghost routes from continuing
     * to poll after an integration is deleted or reloaded.
     */
    fun removeRoute(integrationId: String) {
        val baseRouteId = "integration-download-$integrationId"
        val dlqRouteId = "dlq-$integrationId"
        val toRemove = routesForIntegration(baseRouteId) +
            listOfNotNull(dlqRouteId.takeIf { isRegistered(it) })

        if (toRemove.isEmpty()) {
            log.debug { "removeRoute: no routes found for integration $integrationId" }
            return
        }
        toRemove.forEach { routeId ->
            camelContext.routeController.stopRoute(routeId)
            camelContext.removeRoute(routeId)
            log.info { "Route removed: $routeId" }
        }
    }

    /**
     * Reload a route after its configuration has changed.
     */
    fun reloadRoute(integration: ServiceIntegration) {
        removeRoute(integration.id)
        if (integration.isEnabled) activateRoute(integration)
        log.info { "Route reloaded: ${integration.camelRouteId()}" }
    }

    /**
     * Returns the current Camel route status name for the integration's route,
     * or null if not registered.
     *
     * For multi-directory integrations, reports the status of the first sub-route
     * as a representative value.
     */
    fun routeStatus(integrationId: String): String? {
        val baseRouteId = "integration-download-$integrationId"
        val routeId = routesForIntegration(baseRouteId).firstOrNull() ?: return null
        return camelContext.routeController.getRouteStatus(routeId)?.name
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun registerAndStart(integration: ServiceIntegration, details: IntegrationDetails) {
        camelContext.addRoutes(buildRouteBuilder(integration, details))
    }

    private fun buildRouteBuilder(integration: ServiceIntegration, details: IntegrationDetails): RouteBuilder = when (details) {
        is S3Details -> S3PollingRouteBuilder(integration, details, processor)
        else -> SftpFtpRouteBuilder(integration, details, processor)
    }

    private fun isRegistered(routeId: String): Boolean = camelContext.getRoute(routeId) != null

    /**
     * Returns the IDs of all polling sub-routes for an integration.
     * Matches the base route (`integration-download-{id}`) and all directory
     * sub-routes (`integration-download-{id}-dir0`, `-dir1`, …).
     * DLQ routes are excluded from the result.
     */
    private fun routesForIntegration(baseRouteId: String): List<String> {
        return camelContext.routes
            .map { it.routeId }
            .filter { it.startsWith(baseRouteId) }
    }
}
