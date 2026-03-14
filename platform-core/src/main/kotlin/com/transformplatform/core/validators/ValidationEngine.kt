package com.transformplatform.core.validators

import com.transformplatform.core.spec.model.FieldSpec
import com.transformplatform.core.spec.model.FileSpec
import com.transformplatform.core.spec.model.ParseError
import com.transformplatform.core.spec.model.ParsedRecord
import com.transformplatform.core.spec.model.RuleType
import com.transformplatform.core.spec.model.Severity
import com.transformplatform.core.spec.model.ValidationRule
import mu.KotlinLogging
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@Component
class ValidationEngine {

    fun validate(record: ParsedRecord, spec: FileSpec): ParsedRecord {
        val afterRules = spec.validationRules.fold(record) { acc, rule ->
            checkRule(rule, acc.getFieldAsString(rule.field), acc)
                ?.let { violation ->
                    if (rule.severity == Severity.WARNING || rule.severity == Severity.INFO) {
                        acc.withWarning(violation)
                    } else {
                        acc.withError(violation)
                    }
                } ?: acc
        }
        return spec.fields.fold(afterRules) { acc, fieldSpec ->
            fieldSpecErrors(fieldSpec, acc.getFieldAsString(fieldSpec.name))
                .fold(acc) { result, error -> result.withError(error) }
        }
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
                ruleId = rule.ruleId,
            )
        } else {
            null
        }
    }

    private fun fieldSpecErrors(fieldSpec: FieldSpec, value: String?): List<ParseError> = buildList {
        fieldSpec.validationRegex?.let { regex ->
            if (value != null && !Regex(regex).matches(value)) {
                add(
                    ParseError(
                        field = fieldSpec.name,
                        message = "Field '${fieldSpec.name}' value '${maskedValue(value, fieldSpec)}' does not match required pattern",
                        severity = Severity.ERROR,
                        rawValue = maskedRaw(value, fieldSpec),
                    ),
                )
            }
        }
        fieldSpec.minLength?.let { min ->
            if (value != null && value.length < min) {
                add(
                    ParseError(
                        field = fieldSpec.name,
                        message = "Field '${fieldSpec.name}' length ${value.length} is below minimum $min",
                        severity = Severity.ERROR,
                    ),
                )
            }
        }
        fieldSpec.maxLength?.let { max ->
            if (value != null && value.length > max) {
                add(
                    ParseError(
                        field = fieldSpec.name,
                        message = "Field '${fieldSpec.name}' length ${value.length} exceeds maximum $max",
                        severity = Severity.ERROR,
                    ),
                )
            }
        }
        if (fieldSpec.allowedValues.isNotEmpty() && value != null && value !in fieldSpec.allowedValues) {
            add(
                ParseError(
                    field = fieldSpec.name,
                    message = "Field '${fieldSpec.name}' value '${maskedValue(
                        value,
                        fieldSpec,
                    )}' not in allowed values: ${fieldSpec.allowedValues}",
                    severity = Severity.ERROR,
                ),
            )
        }
    }

    private fun maskedValue(value: String, fieldSpec: FieldSpec) = if (fieldSpec.sensitive) "***" else value

    private fun maskedRaw(value: String?, fieldSpec: FieldSpec) = if (fieldSpec.sensitive) null else value

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
