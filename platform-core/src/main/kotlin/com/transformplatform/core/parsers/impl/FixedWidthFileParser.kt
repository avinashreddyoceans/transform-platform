package com.transformplatform.core.parsers.impl

import com.transformplatform.core.parsers.FileParser
import com.transformplatform.core.spec.model.FieldSpec
import com.transformplatform.core.spec.model.FieldType
import com.transformplatform.core.spec.model.FileFormat
import com.transformplatform.core.spec.model.FileSpec
import com.transformplatform.core.spec.model.ParseError
import com.transformplatform.core.spec.model.ParsedRecord
import com.transformplatform.core.spec.model.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

private val log = KotlinLogging.logger {}

@Component
class FixedWidthFileParser : FileParser {

    override val parserName = "FIXED_WIDTH_PARSER"

    override fun supports(format: FileFormat) = format == FileFormat.FIXED_WIDTH

    override fun validateSpec(spec: FileSpec) {
        require(spec.fields.isNotEmpty()) { "FileSpec must have at least one field" }
        spec.fields.forEach { field ->
            require(field.startPosition != null) {
                "Fixed-width field '${field.name}' must define startPosition"
            }
            require(field.length != null && field.length > 0) {
                "Fixed-width field '${field.name}' must define a positive length"
            }
        }
    }

    override fun parse(input: InputStream, spec: FileSpec): Flow<ParsedRecord> = flow {
        validateSpec(spec)
        val reader = BufferedReader(InputStreamReader(input, spec.encoding))
        var sequence = 0L

        reader.use {
            repeat(spec.skipLinesCount) { reader.readLine() }
            if (spec.hasHeader) reader.readLine()

            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) emit(parseLine(line, spec, sequence++))
                line = reader.readLine()
            }
        }

        log.info { "Fixed-width parsing complete. Total records: $sequence" }
    }

    private fun parseLine(line: String, spec: FileSpec, sequenceNumber: Long): ParsedRecord {
        val fields = mutableMapOf<String, Any?>()
        val errors = mutableListOf<ParseError>()

        spec.fields.forEach { fieldSpec ->
            val start = requireNotNull(fieldSpec.startPosition) {
                "startPosition must be set — guaranteed by validateSpec"
            }
            val end = start + requireNotNull(fieldSpec.length) {
                "length must be set — guaranteed by validateSpec"
            }

            if (start >= line.length) {
                if (fieldSpec.required) {
                    errors.add(
                        ParseError(
                            field = fieldSpec.name,
                            message = "Line too short to extract field '${fieldSpec.name}' at position $start",
                            severity = Severity.ERROR,
                        ),
                    )
                }
                return@forEach
            }

            val rawValue = line.substring(start, minOf(end, line.length)).trim()
            val (coercedValue, error) = coerceValue(rawValue, fieldSpec)
            fields[fieldSpec.name] = coercedValue
            error?.let { errors.add(it) }
        }

        return ParsedRecord(sequenceNumber = sequenceNumber, fields = fields, rawContent = line, errors = errors)
    }

    private fun coerceValue(raw: String, fieldSpec: FieldSpec): Pair<Any?, ParseError?> {
        if (raw.isBlank()) {
            if (fieldSpec.required && !fieldSpec.nullable) {
                return Pair(
                    fieldSpec.defaultValue,
                    ParseError(field = fieldSpec.name, message = "Required field '${fieldSpec.name}' is blank", severity = Severity.ERROR),
                )
            }
            return Pair(fieldSpec.defaultValue, null)
        }

        return runCatching {
            when (fieldSpec.type) {
                FieldType.STRING, FieldType.ALPHANUMERIC -> Pair(raw, null)
                FieldType.INTEGER -> Pair(raw.trim().toInt(), null)
                FieldType.LONG -> Pair(raw.trim().toLong(), null)
                FieldType.DECIMAL -> Pair(raw.trim().toBigDecimal(), null)
                FieldType.AMOUNT -> {
                    val scale = fieldSpec.scale ?: 2
                    val numeric = raw.trim().toLong()
                    val divisor = 10.0.toBigDecimal().pow(scale)
                    Pair(numeric.toBigDecimal().divide(divisor), null)
                }
                FieldType.BOOLEAN -> Pair(raw.trim() in setOf("1", "Y", "T", "true"), null)
                else -> Pair(raw, null)
            }
        }.getOrElse {
            Pair(
                raw,
                ParseError(
                    field = fieldSpec.name,
                    message = "Type conversion failed for '${fieldSpec.name}': '${if (fieldSpec.sensitive) "***" else raw}' " +
                        "is not ${fieldSpec.type}",
                    severity = Severity.ERROR,
                    rawValue = if (fieldSpec.sensitive) null else raw,
                ),
            )
        }
    }
}
