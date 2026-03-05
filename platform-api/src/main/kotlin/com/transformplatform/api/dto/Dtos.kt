package com.transformplatform.api.dto

import com.transformplatform.core.spec.model.*
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.Instant

// ─── Spec DTOs ────────────────────────────────────────────────────────────────

data class CreateSpecRequest(
    @field:NotBlank val name: String,
    val description: String = "",
    val version: String = "1.0",
    @field:NotNull val format: FileFormat,
    val encoding: String = "UTF-8",
    val hasHeader: Boolean = false,
    val delimiter: String? = null,
    val skipLinesCount: Int = 0,
    val fields: List<FieldSpec> = emptyList(),
    val validationRules: List<ValidationRule> = emptyList(),
    val correctionRules: List<CorrectionRule> = emptyList(),
    val outputSpec: OutputSpec? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class SpecResponse(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val format: FileFormat,
    val encoding: String,
    val hasHeader: Boolean,
    val delimiter: String?,
    val fieldCount: Int,
    val validationRuleCount: Int,
    val correctionRuleCount: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    val createdBy: String
)

// ─── Transform DTOs ───────────────────────────────────────────────────────────

data class TransformRequest(
    @field:NotBlank val specId: String,
    val sourceType: SourceType = SourceType.FILE_UPLOAD,
    val sourceFilePath: String? = null,
    val destinationType: String = "KAFKA_TOPIC",
    val kafkaTopic: String? = null,
    val outputFilePath: String? = null,
    val skipInvalidRecords: Boolean = false,
    val delayMs: Long = 0,
    val cronExpression: String? = null,       // for scheduled runs
    val runOnce: Boolean = true
)

enum class SourceType {
    FILE_UPLOAD,
    SFTP,
    S3,
    LOCAL_PATH
}

data class TransformResponse(
    val correlationId: String,
    val status: String,
    val specId: String,
    val fileName: String? = null,
    val totalRecords: Long = 0,
    val successfulRecords: Long = 0,
    val failedRecords: Long = 0,
    val correctedRecords: Long = 0,
    val durationMs: Long = 0,
    val message: String? = null,
    val errors: List<ErrorDetail> = emptyList(),
    val processedAt: Instant = Instant.now()
)

data class ErrorDetail(
    val sequenceNumber: Long,
    val field: String?,
    val message: String,
    val severity: String
)
