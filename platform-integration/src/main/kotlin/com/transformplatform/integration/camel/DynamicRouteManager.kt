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
 *    → build route → `addRoutes()` → immediately `suspendRoute()`.
 *    Suspended routes are registered but not polling; they resume in milliseconds.
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
        var suspended = 0
        var failed = 0

        for (integration in all) {
            try {
                val details = encryptionService.decryptDetails(integration)
                if (integration.isEnabled) {
                    registerAndStart(integration, details)
                    started++
                } else {
                    registerAndSuspend(integration, details)
                    suspended++
                }
            } catch (ex: Exception) {
                log.error(ex) { "Failed to register route for integration '${integration.id}'" }
                failed++
            }
        }

        log.info { "DynamicRouteManager — routes ready: started=$started, suspended=$suspended, failed=$failed" }
    }

    // ── Runtime control ───────────────────────────────────────────────────────

    /**
     * Start polling for a previously disabled (or brand-new) integration.
     */
    fun activateRoute(integration: ServiceIntegration) {
        val routeId = integration.camelRouteId()
        if (isRegistered(routeId)) {
            camelContext.routeController.resumeRoute(routeId)
            log.info { "Route resumed: $routeId" }
        } else {
            val details = encryptionService.decryptDetails(integration)
            registerAndStart(integration, details)
            log.info { "Route registered+started: $routeId" }
        }
    }

    /**
     * Pause polling without removing the route.  Can be resumed instantly.
     */
    fun deactivateRoute(integration: ServiceIntegration) {
        val routeId = integration.camelRouteId()
        if (isRegistered(routeId)) {
            camelContext.routeController.suspendRoute(routeId)
            log.info { "Route suspended: $routeId" }
        } else {
            log.warn { "deactivateRoute called but route not registered: $routeId" }
        }
    }

    /**
     * Fully stop and unregister the route.  Use when deleting an integration.
     */
    fun removeRoute(integrationId: String) {
        val routeId = "integration-download-$integrationId"
        if (isRegistered(routeId)) {
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

    /** Returns the current Camel route status name for the integration's route, or null if not registered. */
    fun routeStatus(integrationId: String): String? {
        val routeId = "integration-download-$integrationId"
        return if (isRegistered(routeId)) camelContext.routeController.getRouteStatus(routeId)?.name else null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun registerAndStart(integration: ServiceIntegration, details: IntegrationDetails) {
        camelContext.addRoutes(buildRouteBuilder(integration, details))
    }

    private fun registerAndSuspend(integration: ServiceIntegration, details: IntegrationDetails) {
        camelContext.addRoutes(buildRouteBuilder(integration, details))
        // Camel auto-starts the route after addRoutes() — immediately suspend it
        camelContext.routeController.suspendRoute(integration.camelRouteId())
    }

    private fun buildRouteBuilder(integration: ServiceIntegration, details: IntegrationDetails): RouteBuilder = when (details) {
        is S3Details -> S3PollingRouteBuilder(integration, details, processor)
        else -> SftpFtpRouteBuilder(integration, details, processor)
    }

    private fun isRegistered(routeId: String): Boolean = camelContext.getRoute(routeId) != null
}
