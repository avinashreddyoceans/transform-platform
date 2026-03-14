package com.transformplatform.integration.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// ── Enums ─────────────────────────────────────────────────────────────────────

/**
 * Whether the integration PULLS files from the remote endpoint (INBOUND)
 * or PUSHES files to it (OUTBOUND).  Phase-1 only implements INBOUND.
 */
enum class Direction { INBOUND, OUTBOUND }

// ── Sealed hierarchy ──────────────────────────────────────────────────────────

/**
 * Type-safe representation of integration connection details.
 *
 * Each subtype corresponds to one [IntegrationType].
 * Serialization/deserialisation is handled by [IntegrationEncryptionService]
 * which uses the outer integration's `type` field as the discriminator —
 * no `@type` field needed in the JSON blob.
 *
 * **Security:** The entire details object (including passwords and secret keys)
 * is AES-256 encrypted before being persisted.  It is NEVER returned verbatim
 * through any API response.
 *
 * **Filters:** Ant-style glob patterns.  An empty list means "match everything".
 * The API request accepts either a proper list OR a comma-separated single string
 * (e.g. `["FILE_A, FILE_B, FILE_C"]`); normalisation happens in [ServiceIntegrationService].
 */
sealed class IntegrationDetails {
    abstract val direction: Direction
    abstract val filters: List<String>

    /** Returns a copy with sanitised (masked) sensitive fields — safe to return in API responses. */
    abstract fun sanitized(): Map<String, Any?>
}

// ── SFTP ──────────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class SftpDetails(
    val host: String,
    val port: Int = 22,
    /** One or more remote directories to poll.  Each gets its own Camel sub-route. */
    val directories: List<String>,
    val userName: String,
    val password: String,
    override val direction: Direction = Direction.INBOUND,
    override val filters: List<String> = emptyList(),
) : IntegrationDetails() {

    override fun sanitized(): Map<String, Any?> = mapOf(
        "host" to host,
        "port" to port,
        "directories" to directories,
        "userName" to userName,
        "password" to "***",
        "direction" to direction.name,
        "filters" to filters,
    )
}

// ── FTP ───────────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class FtpDetails(
    val host: String,
    val port: Int = 21,
    val directories: List<String>,
    val userName: String,
    val password: String,
    override val direction: Direction = Direction.INBOUND,
    override val filters: List<String> = emptyList(),
) : IntegrationDetails() {

    override fun sanitized(): Map<String, Any?> = mapOf(
        "host" to host,
        "port" to port,
        "directories" to directories,
        "userName" to userName,
        "password" to "***",
        "direction" to direction.name,
        "filters" to filters,
    )
}

// ── S3 ────────────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class S3Details(
    val bucketName: String,
    val region: String = "us-east-1",
    val accessKeyId: String,
    val secretAccessKey: String,
    /** Optional key prefix to scope polling (e.g. `inbound/sepa/`). */
    val prefix: String? = null,
    override val direction: Direction = Direction.INBOUND,
    override val filters: List<String> = emptyList(),
) : IntegrationDetails() {

    override fun sanitized(): Map<String, Any?> = mapOf(
        "bucketName" to bucketName,
        "region" to region,
        "accessKeyId" to accessKeyId.take(4) + "****",
        "secretAccessKey" to "***",
        "prefix" to prefix,
        "direction" to direction.name,
        "filters" to filters,
    )
}
