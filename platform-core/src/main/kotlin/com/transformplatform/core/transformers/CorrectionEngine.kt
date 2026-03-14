package com.transformplatform.core.transformers

import com.transformplatform.core.spec.model.CorrectionRule
import com.transformplatform.core.spec.model.CorrectionType
import com.transformplatform.core.spec.model.FieldSpec
import com.transformplatform.core.spec.model.FileSpec
import com.transformplatform.core.spec.model.ParsedRecord
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val log = KotlinLogging.logger {}

@Component
class CorrectionEngine {

    private companion object {
        val CANDIDATE_DATE_FORMATTERS: List<DateTimeFormatter> = listOf(
            "yyyy-MM-dd",
            "MM/dd/yyyy",
            "dd/MM/yyyy",
            "yyyyMMdd",
            "MM-dd-yyyy",
            "dd-MM-yyyy",
            "yyyy/MM/dd",
            "MMddyyyy",
        ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }
    }

    fun applyCorrections(record: ParsedRecord, spec: FileSpec): ParsedRecord {
        if (spec.correctionRules.isEmpty()) return record
        return spec.correctionRules
            .sortedBy { it.applyOrder }
            .fold(record) { acc, rule ->
                val currentValue = acc.getFieldAsString(rule.field)
                val fieldSpec = spec.fields.find { it.name == rule.field }
                val corrected = applyCorrection(rule, currentValue, fieldSpec)
                if (corrected?.toString() != currentValue) {
                    acc.withCorrection(rule.field, currentValue, corrected, rule.correctionType)
                } else {
                    acc
                }
            }
    }

    private fun applyCorrection(rule: CorrectionRule, value: String?, fieldSpec: FieldSpec?): Any? = when (rule.correctionType) {
        CorrectionType.TRIM -> value?.trim()
        CorrectionType.TRIM_LEADING -> value?.trimStart()
        CorrectionType.TRIM_TRAILING -> value?.trimEnd()
        CorrectionType.UPPERCASE -> value?.uppercase()
        CorrectionType.LOWERCASE -> value?.lowercase()
        CorrectionType.TITLE_CASE -> value?.toTitleCase()
        CorrectionType.DATE_FORMAT_COERCE -> coerceDate(value, rule.value ?: fieldSpec?.format)
        CorrectionType.NUMBER_FORMAT_COERCE -> value?.replace(Regex("[$,€£¥\\s]"), "")
        CorrectionType.DEFAULT_IF_NULL -> value ?: rule.value
        CorrectionType.DEFAULT_IF_EMPTY -> if (value.isNullOrBlank()) rule.value else value
        CorrectionType.PAD_LEFT -> rule.value?.toIntOrNull()?.let { value?.padStart(it, '0') } ?: value
        CorrectionType.PAD_RIGHT -> rule.value?.toIntOrNull()?.let { value?.padEnd(it, ' ') } ?: value
        CorrectionType.REMOVE_SPECIAL_CHARS -> value?.replace(Regex("[^a-zA-Z0-9 ]"), "")
        CorrectionType.REGEX_REPLACE -> {
            val parts = rule.value?.split("->")?.takeIf { it.size == 2 }
            if (parts == null) value else value?.replace(Regex(parts[0].trim()), parts[1].trim())
        }
    }

    private fun coerceDate(value: String?, targetFormat: String?): String? {
        if (value.isNullOrBlank()) return value
        val target = DateTimeFormatter.ofPattern(targetFormat ?: "yyyy-MM-dd", Locale.ENGLISH)
        return CANDIDATE_DATE_FORMATTERS
            .firstNotNullOfOrNull { formatter ->
                runCatching { LocalDate.parse(value, formatter) }.getOrNull()
            }
            ?.format(target)
            ?: run {
                log.warn { "Could not coerce date '$value' to format '$targetFormat'" }
                value
            }
    }

    private fun String.toTitleCase(): String = split(" ").joinToString(" ") {
        it.lowercase().replaceFirstChar { c -> c.titlecase(Locale.ENGLISH) }
    }
}
