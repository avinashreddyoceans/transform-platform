package com.transformplatform.integration.service

import com.transformplatform.integration.camel.DynamicRouteManager
import com.transformplatform.integration.model.IntegrationDetails
import com.transformplatform.integration.model.IntegrationType
import com.transformplatform.integration.model.ServiceIntegration
import com.transformplatform.integration.repository.ServiceIntegrationRepository
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * Business logic for managing [ServiceIntegration] lifecycle.
 *
 * Every mutating operation that affects routing configuration triggers a live
 * reload in [DynamicRouteManager] so changes take effect immediately without
 * application restart.
 *
 * **Filter normalisation:**  The API accepts filters as either a proper list or
 * a single comma-separated string (e.g. `["FILE_A, FILE_B"]`).
 * [normaliseFilters] flattens and trims these into a clean `List<String>`.
 */
@Service
class ServiceIntegrationService(
    private val repo: ServiceIntegrationRepository,
    private val encryptionService: IntegrationEncryptionService,
    private val routeManager: DynamicRouteManager,
) {

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    fun create(
        type: IntegrationType,
        userId: String,
        shortDescription: String,
        isEnabled: Boolean = false,
        details: IntegrationDetails,
        updatedBy: String,
    ): ServiceIntegration {
        // Temporary ID for salt — we'll re-encrypt after the real ID is assigned
        val placeholder = ServiceIntegration(
            type = type,
            userId = userId,
            shortDescription = shortDescription,
            isEnabled = false, // always start disabled
            encryptedDetails = "", // placeholder
            updatedBy = updatedBy,
        )
        val saved = repo.save(placeholder)

        // Now encrypt with the real ID as salt
        saved.encryptedDetails = encryptionService.encryptDetails(saved.id, details)
        val result = repo.save(saved)

        log.info { "Created integration: id=${result.id}, type=$type, userId=$userId" }

        // If caller requested enabled=true, activate now
        if (isEnabled) {
            result.isEnabled = true
            result.updatedAt = Instant.now()
            repo.save(result)
            routeManager.activateRoute(result)
        }

        return result
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun findAll(): List<ServiceIntegration> = repo.findAll()

    @Transactional(readOnly = true)
    fun findById(id: String): ServiceIntegration = findOrThrow(id)

    @Transactional(readOnly = true)
    fun findByUserId(userId: String): List<ServiceIntegration> = repo.findAllByUserId(userId)

    // ── Update ────────────────────────────────────────────────────────────────

    @Transactional
    fun update(id: String, shortDescription: String? = null, details: IntegrationDetails? = null, updatedBy: String): ServiceIntegration {
        val integration = findOrThrow(id)

        shortDescription?.let { integration.shortDescription = it }
        details?.let { integration.encryptedDetails = encryptionService.encryptDetails(id, it) }
        integration.updatedBy = updatedBy
        integration.updatedAt = Instant.now()

        val saved = repo.save(integration)

        // Reload route so changes (new filters, new host, etc.) take effect immediately
        routeManager.reloadRoute(saved)

        log.info { "Updated integration: id=$id" }
        return saved
    }

    // ── Enable / Disable ──────────────────────────────────────────────────────

    @Transactional
    fun enable(id: String, updatedBy: String): ServiceIntegration {
        val integration = findOrThrow(id)
        check(!integration.isEnabled) { "Integration $id is already enabled" }

        integration.isEnabled = true
        integration.updatedBy = updatedBy
        integration.updatedAt = Instant.now()
        val saved = repo.save(integration)

        routeManager.activateRoute(saved)
        log.info { "Enabled integration: id=$id" }
        return saved
    }

    @Transactional
    fun disable(id: String, updatedBy: String): ServiceIntegration {
        val integration = findOrThrow(id)
        check(integration.isEnabled) { "Integration $id is already disabled" }

        integration.isEnabled = false
        integration.updatedBy = updatedBy
        integration.updatedAt = Instant.now()
        val saved = repo.save(integration)

        routeManager.deactivateRoute(saved)
        log.info { "Disabled integration: id=$id" }
        return saved
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    fun delete(id: String) {
        val integration = findOrThrow(id)
        routeManager.removeRoute(id)
        repo.delete(integration)
        log.info { "Deleted integration: id=$id" }
    }

    // ── Decrypted view (for route building, never for API responses) ──────────

    @Transactional(readOnly = true)
    fun decryptDetails(id: String): IntegrationDetails = encryptionService.decryptDetails(findOrThrow(id))

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun findOrThrow(id: String): ServiceIntegration =
        repo.findById(id).orElseThrow { NoSuchElementException("Integration not found: $id") }

    companion object {
        /**
         * Normalises filter input.  Accepts:
         * - `["SEPA_CREDIT_*"]`
         * - `["FILE_A, FILE_B, FILE_C"]`  (comma-separated in a single string)
         * - `["FILE_A", "FILE_B", "FILE_C"]`
         *
         * Returns a clean `List<String>` with one entry per pattern, stripped of spaces.
         */
        fun normaliseFilters(raw: List<String>): List<String> = raw.flatMap { entry -> entry.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
