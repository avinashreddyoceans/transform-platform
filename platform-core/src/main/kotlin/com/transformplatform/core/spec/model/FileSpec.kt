package com.transformplatform.core.spec.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

/**
 * The root spec that describes any file format.
 * This single model drives all parsing, validation, transformation and writing.
 */
data class FileSpec(
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "1.0",
    val format: FileFormat,
    val encoding: String = "UTF-8",
    val hasHeader: Boolean = false,
    val delimiter: String? = null,          // CSV: comma, pipe, tab etc
    val recordSeparator: String = "\n",
    val skipLinesCount: Int = 0,            // skip N lines at top of file
    val fields: List<FieldSpec>,
    val validationRules: List<ValidationRule> = emptyList(),
    val correctionRules: List<CorrectionRule> = emptyList(),
    val outputSpec: OutputSpec? = null,
    val metadata: Map<String, String> = emptyMap(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val createdBy: String = "system"
)

enum class FileFormat {
    CSV,
    FIXED_WIDTH,
    XML,
    JSON,
    NACHA,
    ISO20022,
    SWIFT_MT,
    DELIMITED,      // any custom delimiter
    CUSTOM
}

/**
 * Describes a single field in the file.
 * Works for all formats - unused fields for a format are simply ignored.
 */
data class FieldSpec(
    val name: String,
    val displayName: String = name,
    val type: FieldType,

    // Fixed-width positioning
    val startPosition: Int? = null,
    val length: Int? = null,

    // CSV / Delimited
    val columnIndex: Int? = null,
    val columnName: String? = null,       // when hasHeader = true

    // XML / JSON
    val path: String? = null,             // XPath for XML, JSONPath for JSON
    val xmlAttribute: String? = null,     // for XML attributes

    // Common
    val required: Boolean = true,
    val nullable: Boolean = false,
    val defaultValue: String? = null,
    val format: String? = null,           // date format, number format etc
    val scale: Int? = null,               // decimal places for AMOUNT/DECIMAL
    val validationRegex: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val allowedValues: List<String> = emptyList(),
    val sensitive: Boolean = false,       // PII — will be masked in logs
    val description: String = ""
)

enum class FieldType {
    STRING,
    INTEGER,
    LONG,
    DECIMAL,
    AMOUNT,           // monetary value with scale
    DATE,
    DATETIME,
    BOOLEAN,
    ALPHANUMERIC,
    ROUTING_NUMBER,   // domain-aware: bank routing
    ACCOUNT_NUMBER,   // domain-aware: bank account
    ABA,
    ENUM
}

/**
 * Validation rules applied after parsing.
 */
data class ValidationRule(
    val ruleId: String,
    val field: String,
    val ruleType: RuleType,
    val value: String? = null,
    val secondaryValue: String? = null,   // for BETWEEN rules
    val message: String,
    val severity: Severity = Severity.ERROR
)

enum class RuleType {
    NOT_NULL,
    NOT_EMPTY,
    MIN_LENGTH,
    MAX_LENGTH,
    EXACT_LENGTH,
    REGEX,
    MIN_VALUE,
    MAX_VALUE,
    BETWEEN,
    ALLOWED_VALUES,
    CUSTOM_EXPRESSION,  // SpEL expression
    CROSS_FIELD         // validate field A against field B
}

enum class Severity {
    INFO,
    WARNING,
    ERROR,
    FATAL   // stops processing the entire file
}

/**
 * Auto-correction rules applied before or after validation.
 */
data class CorrectionRule(
    val ruleId: String,
    val field: String,
    val correctionType: CorrectionType,
    val value: String? = null,
    val applyOrder: Int = 0
)

enum class CorrectionType {
    TRIM,
    TRIM_LEADING,
    TRIM_TRAILING,
    UPPERCASE,
    LOWERCASE,
    TITLE_CASE,
    DATE_FORMAT_COERCE,     // try to parse any date format and normalize
    NUMBER_FORMAT_COERCE,   // strip commas, currency symbols
    DEFAULT_IF_NULL,
    DEFAULT_IF_EMPTY,
    PAD_LEFT,
    PAD_RIGHT,
    REMOVE_SPECIAL_CHARS,
    REGEX_REPLACE
}

/**
 * Describes the output format when transforming from one spec to another.
 */
data class OutputSpec(
    val format: FileFormat,
    val delimiter: String? = null,
    val encoding: String = "UTF-8",
    val hasHeader: Boolean = false,
    val fieldMappings: List<FieldMapping> = emptyList()
)

data class FieldMapping(
    val sourceField: String,
    val targetField: String,
    val transformation: String? = null    // SpEL or simple expression
)
