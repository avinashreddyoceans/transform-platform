package com.transformplatform.api.controller.integration

import com.transformplatform.integration.model.Direction
import com.transformplatform.integration.model.FtpDetails
import com.transformplatform.integration.model.IntegrationDetails
import com.transformplatform.integration.model.IntegrationType
import com.transformplatform.integration.model.S3Details
import com.transformplatform.integration.model.ServiceIntegration
import com.transformplatform.integration.model.SftpDetails
import com.transformplatform.integration.service.IntegrationEncryptionService
import com.transformplatform.integration.service.ServiceIntegrationService
import com.transformplatform.integration.service.ServiceIntegrationService.Companion.normaliseFilters
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// ── Request DTOs ──────────────────────────────────────────────────────────────

/**
 * Unified onboarding request for SFTP, FTP, and S3 integrations.
 *
 * The `details` map is type-specific and validated at the service layer.
 *
 * **SFTP / FTP details keys:**
 * ```json
 * {
 *   "host": "sftp.bank.com",
 *   "port": 22,
 *   "directories": ["outbox"],
 *   "userName": "feed_user",
 *   "password": "s3cr3t",
 *   "direction": "INBOUND",
 *   "filters": ["SEPA_CREDIT_*, DAILY_REPORT_*"]
 * }
 * ```
 * **S3 details keys:**
 * ```json
 * {
 *   "bucketName": "my-feeds",
 *   "region": "eu-west-1",
 *   "accessKeyId": "AKID...",
 *   "secretAccessKey": "...",
 *   "prefix": "inbound/",
 *   "direction": "INBOUND",
 *   "filters": ["SEPA_CREDIT_*"]
 * }
 * ```
 */
data class CreateIntegrationRequest(
    @field:NotBlank val type: String,
    @field:NotBlank val userId: String,
    @field:NotBlank val shortDescription: String,
    val isEnabled: Boolean = false,
    val details: Map<String, Any?>,
    @field:NotBlank val updatedBy: String,
)

data class UpdateIntegrationRequest(
    val shortDescription: String? = null,
    val details: Map<String, Any?>? = null,
    @field:NotBlank val updatedBy: String,
)

// ── Response DTO ──────────────────────────────────────────────────────────────

/**
 * Safe response — [encryptedDetails] is NEVER included.
 * The `details` map contains sanitised values (passwords masked as `***`).
 */
data class ServiceIntegrationResponse(
    val id: String,
    val type: String,
    val userId: String,
    val shortDescription: String,
    val isEnabled: Boolean,
    /** Sanitised details — passwords and secret keys replaced with `***`. */
    val details: Map<String, Any?>,
    val updatedBy: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(integration: ServiceIntegration, decryptedDetails: IntegrationDetails) = ServiceIntegrationResponse(
            id = integration.id,
            type = integration.type.name,
            userId = integration.userId,
            shortDescription = integration.shortDescription,
            isEnabled = integration.isEnabled,
            details = decryptedDetails.sanitized(),
            updatedBy = integration.updatedBy,
            createdAt = integration.createdAt,
            updatedAt = integration.updatedAt,
        )
    }
}

// ── Controller ────────────────────────────────────────────────────────────────

/**
 * REST controller for integration onboarding and lifecycle management.
 *
 * Endpoints:
 * ```
 * GET    /api/v1/integrations              — list all
 * POST   /api/v1/integrations              — onboard new integration
 * GET    /api/v1/integrations/{id}         — get one
 * PUT    /api/v1/integrations/{id}         — update description or details
 * DELETE /api/v1/integrations/{id}         — remove integration + stop Camel route
 * POST   /api/v1/integrations/{id}/enable  — start polling
 * POST   /api/v1/integrations/{id}/disable — pause polling
 * ```
 */
@RestController
@RequestMapping("/api/v1/integrations")
class ServiceIntegrationController(
    private val service: ServiceIntegrationService,
    private val encryptionService: IntegrationEncryptionService,
) {

    @GetMapping
    fun listIntegrations(): ResponseEntity<List<ServiceIntegrationResponse>> {
        val result = service.findAll().map { integration ->
            val details = encryptionService.decryptDetails(integration)
            ServiceIntegrationResponse.from(integration, details)
        }
        return ResponseEntity.ok(result)
    }

    @GetMapping("/{id}")
    fun getIntegration(@PathVariable id: String): ResponseEntity<ServiceIntegrationResponse> = try {
        val integration = service.findById(id)
        val details = encryptionService.decryptDetails(integration)
        ResponseEntity.ok(ServiceIntegrationResponse.from(integration, details))
    } catch (e: NoSuchElementException) {
        ResponseEntity.notFound().build()
    }

    @PostMapping
    fun createIntegration(@Valid @RequestBody req: CreateIntegrationRequest): ResponseEntity<ServiceIntegrationResponse> {
        val type = try {
            IntegrationType.valueOf(req.type.uppercase())
        } catch (e: IllegalArgumentException) {
            return ResponseEntity.badRequest().build()
        }

        val details = try {
            parseDetails(type, req.details)
        } catch (e: Exception) {
            return ResponseEntity.badRequest().build()
        }

        val integration = service.create(
            type = type,
            userId = req.userId,
            shortDescription = req.shortDescription,
            isEnabled = req.isEnabled,
            details = details,
            updatedBy = req.updatedBy,
        )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ServiceIntegrationResponse.from(integration, details))
    }

    @PutMapping("/{id}")
    fun updateIntegration(
        @PathVariable id: String,
        @Valid @RequestBody req: UpdateIntegrationRequest,
    ): ResponseEntity<ServiceIntegrationResponse> = try {
        val integration = service.findById(id)
        val newDetails = req.details?.let {
            parseDetails(integration.type, it)
        }

        val updated = service.update(
            id = id,
            shortDescription = req.shortDescription,
            details = newDetails,
            updatedBy = req.updatedBy,
        )
        val resolvedDetails = encryptionService.decryptDetails(updated)
        ResponseEntity.ok(ServiceIntegrationResponse.from(updated, resolvedDetails))
    } catch (e: NoSuchElementException) {
        ResponseEntity.notFound().build()
    } catch (e: Exception) {
        ResponseEntity.badRequest().build()
    }

    @DeleteMapping("/{id}")
    fun deleteIntegration(@PathVariable id: String): ResponseEntity<Void> = try {
        service.delete(id)
        ResponseEntity.noContent().build()
    } catch (e: NoSuchElementException) {
        ResponseEntity.notFound().build()
    }

    @PostMapping("/{id}/enable")
    fun enableIntegration(@PathVariable id: String, @RequestParam updatedBy: String): ResponseEntity<ServiceIntegrationResponse> = try {
        val updated = service.enable(id, updatedBy)
        val details = encryptionService.decryptDetails(updated)
        ResponseEntity.ok(ServiceIntegrationResponse.from(updated, details))
    } catch (e: NoSuchElementException) {
        ResponseEntity.notFound().build()
    } catch (e: IllegalStateException) {
        ResponseEntity.badRequest().build()
    }

    @PostMapping("/{id}/disable")
    fun disableIntegration(@PathVariable id: String, @RequestParam updatedBy: String): ResponseEntity<ServiceIntegrationResponse> = try {
        val updated = service.disable(id, updatedBy)
        val details = encryptionService.decryptDetails(updated)
        ResponseEntity.ok(ServiceIntegrationResponse.from(updated, details))
    } catch (e: NoSuchElementException) {
        ResponseEntity.notFound().build()
    } catch (e: IllegalStateException) {
        ResponseEntity.badRequest().build()
    }

    // ── Details parser ────────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun parseDetails(type: IntegrationType, raw: Map<String, Any?>): IntegrationDetails {
        val direction = (raw["direction"] as? String)?.let { Direction.valueOf(it.uppercase()) }
            ?: Direction.INBOUND

        // Accept filters as proper list OR comma-separated single-string
        val rawFilters = when (val f = raw["filters"]) {
            is List<*> -> (f as List<*>).filterIsInstance<String>()
            is String -> listOf(f)
            else -> emptyList()
        }
        val filters = normaliseFilters(rawFilters)

        return when (type) {
            IntegrationType.SFTP -> SftpDetails(
                host = raw["host"] as? String ?: error("SFTP requires host"),
                port = (raw["port"] as? Number)?.toInt() ?: 22,
                directories = (raw["directories"] as? List<*>)
                    ?.filterIsInstance<String>() ?: listOf("/"),
                userName = raw["userName"] as? String ?: error("SFTP requires userName"),
                password = raw["password"] as? String ?: error("SFTP requires password"),
                direction = direction,
                filters = filters,
            )
            IntegrationType.FTP -> FtpDetails(
                host = raw["host"] as? String ?: error("FTP requires host"),
                port = (raw["port"] as? Number)?.toInt() ?: 21,
                directories = (raw["directories"] as? List<*>)
                    ?.filterIsInstance<String>() ?: listOf("/"),
                userName = raw["userName"] as? String ?: error("FTP requires userName"),
                password = raw["password"] as? String ?: error("FTP requires password"),
                direction = direction,
                filters = filters,
            )
            IntegrationType.S3 -> S3Details(
                bucketName = raw["bucketName"] as? String ?: error("S3 requires bucketName"),
                region = raw["region"] as? String ?: "us-east-1",
                accessKeyId = raw["accessKeyId"] as? String ?: error("S3 requires accessKeyId"),
                secretAccessKey = raw["secretAccessKey"] as? String ?: error("S3 requires secretAccessKey"),
                prefix = raw["prefix"] as? String,
                direction = direction,
                filters = filters,
            )
        }
    }
}
