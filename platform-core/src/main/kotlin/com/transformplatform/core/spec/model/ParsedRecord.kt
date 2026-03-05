package com.transformplatform.core.spec.model

import java.time.Instant

/**
 * Universal record that flows through the entire pipeline regardless of source format.
 * Every parser produces ParsedRecords. Every writer consumes ParsedRecords.
 * This is the internal currency of the platform.
 */
data class ParsedRecord(
    val sequenceNumber: Long,
    val fields: Map<String, Any?>,
    val rawContent: String? = null,           // original raw line/element
    val errors: List<ParseError> = emptyList(),
    val warnings: List<ParseError> = emptyList(),
    val corrected: Boolean = false,
    val corrections: List<AppliedCorrection> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val processedAt: Instant = Instant.now()
) {
    val isValid: Boolean get() = errors.isEmpty()
    val hasFatalErrors: Boolean get() = errors.any { it.severity == Severity.FATAL }

    fun getField(name: String): Any? = fields[name]

    fun getFieldAsString(name: String): String? = fields[name]?.toString()

    fun withCorrection(field: String, oldValue: Any?, newValue: Any?, type: CorrectionType): ParsedRecord {
        val updatedFields = fields.toMutableMap()
        updatedFields[field] = newValue
        val correction = AppliedCorrection(field, oldValue?.toString(), newValue?.toString(), type)
        return copy(
            fields = updatedFields,
            corrected = true,
            corrections = corrections + correction
        )
    }

    fun withError(error: ParseError): ParsedRecord = copy(errors = errors + error)

    fun withWarning(warning: ParseError): ParsedRecord = copy(warnings = warnings + warning)
}

data class ParseError(
    val field: String,
    val message: String,
    val severity: Severity,
    val rawValue: String? = null,
    val ruleId: String? = null
)

data class AppliedCorrection(
    val field: String,
    val originalValue: String?,
    val correctedValue: String?,
    val correctionType: CorrectionType
)

/**
 * Summary produced after processing an entire file.
 */
data class ProcessingResult(
    val specId: String,
    val fileName: String,
    val totalRecords: Long,
    val successfulRecords: Long,
    val failedRecords: Long,
    val correctedRecords: Long,
    val warnings: Long,
    val startedAt: Instant,
    val completedAt: Instant,
    val status: ProcessingStatus,
    val errors: List<ProcessingError> = emptyList()
) {
    val durationMs: Long get() = completedAt.toEpochMilli() - startedAt.toEpochMilli()
    val successRate: Double get() = if (totalRecords > 0) successfulRecords.toDouble() / totalRecords else 0.0
}

enum class ProcessingStatus {
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    COMPLETED_WITH_ERRORS,
    FAILED,
    PARTIAL
}

data class ProcessingError(
    val sequenceNumber: Long,
    val field: String?,
    val message: String,
    val severity: Severity
)
