package com.transformplatform.integration.model

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

// ── Enum ──────────────────────────────────────────────────────────────────────

enum class IntegrationType { SFTP, FTP, S3 }

// ── Entity ────────────────────────────────────────────────────────────────────

/**
 * Represents one onboarded connector integration.
 *
 * API request shape (accepted by POST /api/v1/integrations):
 * ```json
 * {
 *   "type": "SFTP",
 *   "userId": "p1-dev-group",
 *   "shortDescription": "SFTP Profile for SEPA INBOUND Files",
 *   "isEnabled": true,
 *   "details": {
 *     "host": "sftp.bank.com",
 *     "directories": ["outbox"],
 *     "port": 22,
 *     "userName": "feed_user",
 *     "password": "s3cr3t",
 *     "direction": "INBOUND",
 *     "filters": ["SEPA_CREDIT_*, DAILY_REPORT_*"]
 *   },
 *   "updatedBy": "p1-dev-group"
 * }
 * ```
 *
 * **Security:**  All sensitive fields (passwords, keys) live inside [encryptedDetails]
 * which is an AES-256 encrypted JSON string — it is NEVER returned through any API.
 *
 * **Routing:**  [DynamicRouteManager] reads all integrations on startup and starts
 * an Apache Camel polling route for every row where [isEnabled] = true.
 */
@Entity
@Table(name = "service_integrations")
class ServiceIntegration(

    @Id
    val id: String = UUID.randomUUID().toString(),

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: IntegrationType,

    @Column(name = "user_id", nullable = false)
    var userId: String,

    @Column(name = "short_description", nullable = false)
    var shortDescription: String,

    /** When true, the Camel polling route is RUNNING.  False → route is suspended. */
    @Column(name = "is_enabled", nullable = false)
    var isEnabled: Boolean = false,

    /**
     * AES-256 encrypted JSON.  Shape depends on [type]:
     *  - SFTP → [SftpDetails]
     *  - FTP  → [FtpDetails]
     *  - S3   → [S3Details]
     *
     * Decrypt via [IntegrationEncryptionService.decryptDetails].
     * NEVER log or return this column.
     */
    @Column(name = "encrypted_details", nullable = false, columnDefinition = "TEXT")
    var encryptedDetails: String,

    @Column(name = "updated_by", nullable = false)
    var updatedBy: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    @Column(nullable = false)
    var version: Int = 1,

) {
    /** Stable Camel route ID — used to suspend / resume / remove the route by name. */
    fun camelRouteId(): String = "integration-download-$id"
}
