package com.transformplatform.integration.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.transformplatform.integration.model.FtpDetails
import com.transformplatform.integration.model.IntegrationDetails
import com.transformplatform.integration.model.IntegrationType
import com.transformplatform.integration.model.S3Details
import com.transformplatform.integration.model.ServiceIntegration
import com.transformplatform.integration.model.SftpDetails
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.security.crypto.encrypt.TextEncryptor
import org.springframework.stereotype.Service

private val log = KotlinLogging.logger {}

/**
 * Encrypts and decrypts the [ServiceIntegration.encryptedDetails] payload.
 *
 * **Algorithm:** AES-256/CBC via Spring Security's [Encryptors.text].
 * **Key source:** `transform-platform.security.credential-key` (defaults to
 *   `jwt-secret` for local dev — use a dedicated KMS-derived secret in prod).
 * **Salt:** The integration's UUID is used as the per-row AES salt so that two
 *   integrations with identical payloads produce different ciphertexts.
 *
 * The outer [ServiceIntegration.type] acts as the type discriminator during
 * deserialisation, so no `@type` field is embedded in the JSON blob.
 */
@Service
class IntegrationEncryptionService(
    @Value("\${transform-platform.security.credential-key}")
    private val encryptionKey: String,
    private val objectMapper: ObjectMapper,
) {

    // ── Encrypt ───────────────────────────────────────────────────────────────

    fun encryptDetails(integrationId: String, details: IntegrationDetails): String {
        val json = objectMapper.writeValueAsString(details)
        return encryptor(integrationId).encrypt(json)
    }

    // ── Decrypt ───────────────────────────────────────────────────────────────

    /**
     * Decrypts and deserialises the details for the given integration.
     * The [ServiceIntegration.type] field is used to pick the correct target class.
     *
     * **Never log the return value of this method.**
     */
    fun decryptDetails(integration: ServiceIntegration): IntegrationDetails =
        decryptDetails(integration.id, integration.type, integration.encryptedDetails)

    fun decryptDetails(integrationId: String, type: IntegrationType, encryptedDetails: String): IntegrationDetails {
        val json = encryptor(integrationId).decrypt(encryptedDetails)
        return when (type) {
            IntegrationType.SFTP -> objectMapper.readValue<SftpDetails>(json)
            IntegrationType.FTP -> objectMapper.readValue<FtpDetails>(json)
            IntegrationType.S3 -> objectMapper.readValue<S3Details>(json)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Spring Security's text encryptor requires an even-length hex string as salt.
     * We derive it by stripping hyphens from the UUID and padding to 32 chars.
     */
    private fun encryptor(integrationId: String): TextEncryptor = Encryptors.text(encryptionKey, hexSalt(integrationId))

    private fun hexSalt(id: String): String = id.replace("-", "").take(32).padEnd(32, '0')
}
