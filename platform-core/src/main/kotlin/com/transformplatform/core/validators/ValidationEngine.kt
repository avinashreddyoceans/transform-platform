package com.transformplatform.core.validators

import com.transformplatform.core.spec.model.*
import mu.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

/**
 * Runs all ValidationRules defined in the FileSpec against each ParsedRecord.
 * Adds errors/warnings to the record without throwing — the pipeline decides what to do with them.
 */
@Component
class ValidationEngine {

    fun validate(record: ParsedRecord, spec: FileSpec): ParsedRecord {
        var result = record

        spec.validationRules.forEach { rule ->
            val value = record.getFieldAsString(rule.field)
            val violation = checkRule(rule, value, record)
            if (violation != null) {
                result = when (rule.severity) {
                    Severity.WARNING, Severity.INFO -> result.withWarning(violation)
                    else -> result.withError(violation)
                }
            }
        }

        // Also run per-field spec validation (regex, min/maxLength, allowedValues)
        spec.fields.forEach { fieldSpec ->
            val value = record.getFieldAsString(fieldSpec.name)
            val errors = validateFieldSpec(fieldSpec, value)
            errors.forEach { result = result.withError(it) }
        }

        return result
    }

    private fun checkRule(rule: ValidationRule, value: String?, record: ParsedRecord): ParseError? {
        val violated = when (rule.ruleType) {
            RuleType.NOT_NULL -> value == null
            RuleType.NOT_EMPTY -> value.isNullOrBlank()
            RuleType.MIN_LENGTH -> value != null && value.length < (rule.value?.toIntOrNull() ?: 0)
            RuleType.MAX_LENGTH -> value != null && value.length > (rule.value?.toIntOrNull() ?: Int.MAX_VALUE)
            RuleType.EXACT_LENGTH -> value != null && value.length != (rule.value?.toIntOrNull() ?: -1)
            RuleType.REGEX -> value != null && !Regex(rule.value ?: ".*").matches(value)
            RuleType.MIN_VALUE -> {
                val num = value?.toBigDecimalOrNull()
                val min = rule.value?.toBigDecimalOrNull()
                num != null && min != null && num < min
            }
            RuleType.MAX_VALUE -> {
                val num = value?.toBigDecimalOrNull()
                val max = rule.value?.toBigDecimalOrNull()
                num != null && max != null && num > max
            }
            RuleType.BETWEEN -> {
                val num = value?.toBigDecimalOrNull()
                val min = rule.value?.toBigDecimalOrNull()
                val max = rule.secondaryValue?.toBigDecimalOrNull()
                num != null && min != null && max != null && (num < min || num > max)
            }
            RuleType.ALLOWED_VALUES -> {
                val allowed = rule.value?.split(",")?.map { it.trim() } ?: emptyList()
                value != null && allowed.isNotEmpty() && value !in allowed
            }
            RuleType.CUSTOM_EXPRESSION -> evaluateSpel(rule.value, value, record)
            RuleType.CROSS_FIELD -> evaluateCrossField(rule, record)
        }

        return if (violated) {
            ParseError(
                field = rule.field,
                message = rule.message,
                severity = rule.severity,
                rawValue = value,
                ruleId = rule.ruleId
            )
        } else null
    }

    private fun validateFieldSpec(fieldSpec: FieldSpec, value: String?): List<ParseError> {
        val errors = mutableListOf<ParseError>()

        fieldSpec.validationRegex?.let { regex ->
            if (value != null && !Regex(regex).matches(value)) {
                errors.add(ParseError(
                    field = fieldSpec.name,
                    message = "Field '${fieldSpec.name}' value '${if (fieldSpec.sensitive) "***" else value}' does not match required pattern",
                    severity = Severity.ERROR,
                    rawValue = if (fieldSpec.sensitive) null else value
                ))
            }
        }

        fieldSpec.minLength?.let { min ->
            if (value != null && value.length < min) {
                errors.add(ParseError(
                    field = fieldSpec.name,
                    message = "Field '${fieldSpec.name}' length ${value.length} is below minimum $min",
                    severity = Severity.ERROR
                ))
            }
        }

        fieldSpec.maxLength?.let { max ->
            if (value != null && value.length > max) {
                errors.add(ParseError(
                    field = fieldSpec.name,
                    message = "Field '${fieldSpec.name}' length ${value.length} exceeds maximum $max",
                    severity = Severity.ERROR
                ))
            }
        }

        if (fieldSpec.allowedValues.isNotEmpty() && value != null) {
            if (value !in fieldSpec.allowedValues) {
                errors.add(ParseError(
                    field = fieldSpec.name,
                    message = "Field '${fieldSpec.name}' value '${if (fieldSpec.sensitive) "***" else value}' not in allowed values: ${fieldSpec.allowedValues}",
                    severity = Severity.ERROR
                ))
            }
        }

        return errors
    }

    // SpEL evaluation for custom expressions
    private fun evaluateSpel(expression: String?, value: String?, record: ParsedRecord): Boolean {
        // TODO: Phase 2 — integrate Spring Expression Language for complex rules
        log.warn { "SpEL expressions not yet implemented — rule skipped" }
        return false
    }

    private fun evaluateCrossField(rule: ValidationRule, record: ParsedRecord): Boolean {
        // TODO: Phase 2 — cross-field validation logic
        log.warn { "Cross-field validation not yet implemented — rule skipped" }
        return false
    }
}
