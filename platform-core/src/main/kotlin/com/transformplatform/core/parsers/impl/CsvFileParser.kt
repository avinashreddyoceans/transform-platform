package com.transformplatform.core.parsers.impl

import com.transformplatform.core.parsers.FileParser
import com.transformplatform.core.spec.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

private val log = KotlinLogging.logger {}

@Component
class CsvFileParser : FileParser {

    private companion object {
        val BOOLEAN_TRUE_VALUES = setOf("true", "1", "yes", "y")
    }

    override val parserName = "CSV_PARSER"

    override fun supports(format: FileFormat) = format == FileFormat.CSV || format == FileFormat.DELIMITED

    override fun validateSpec(spec: FileSpec) {
        require(spec.fields.isNotEmpty()) { "FileSpec must have at least one field" }
        spec.fields.forEach { field ->
            require(field.columnIndex != null || field.columnName != null) {
                "CSV field '${field.name}' must have either columnIndex or columnName"
            }
        }
    }

    override fun parse(input: InputStream, spec: FileSpec): Flow<ParsedRecord> = flow {
        validateSpec(spec)
        val delimiter = spec.delimiter ?: ","
        val reader = BufferedReader(InputStreamReader(input, spec.encoding))
        var sequence = 0L

        reader.use {
            repeat(spec.skipLinesCount) { reader.readLine() }

            val headerIndexMap = if (spec.hasHeader) {
                reader.readLine()
                    ?.let { parseHeader(it, delimiter) }
                    .also { log.debug { "Parsed CSV header: $it" } }
                    ?: emptyMap()
            } else emptyMap()

            var line = reader.readLine()
            while (line != null) {
                if (line.isNotBlank()) emit(parseLine(line, spec, sequence++, delimiter, headerIndexMap))
                line = reader.readLine()
            }
        }

        log.info { "CSV parsing complete. Total records: $sequence" }
    }

    private fun parseHeader(headerLine: String, delimiter: String): Map<String, Int> =
        headerLine.split(delimiter)
            .mapIndexed { index, name -> name.trim().lowercase() to index }
            .toMap()

    private fun parseLine(
        line: String,
        spec: FileSpec,
        sequenceNumber: Long,
        delimiter: String,
        headerIndexMap: Map<String, Int>
    ): ParsedRecord {
        val values = splitRespectingQuotes(line, delimiter)
        val fields = mutableMapOf<String, Any?>()
        val errors = mutableListOf<ParseError>()

        spec.fields.forEach { fieldSpec ->
            val index = resolveColumnIndex(fieldSpec, headerIndexMap)
            if (index == null) {
                if (fieldSpec.required)
                    errors.add(ParseError(
                        field = fieldSpec.name,
                        message = "Column '${fieldSpec.columnName ?: fieldSpec.name}' not found in header",
                        severity = Severity.ERROR
                    ))
                return@forEach
            }

            val rawValue = values.getOrNull(index)?.trim()?.removeSurrounding("\"")
            val (coercedValue, error) = coerceValue(rawValue, fieldSpec)
            fields[fieldSpec.name] = coercedValue
            error?.let { errors.add(it) }
        }

        return ParsedRecord(sequenceNumber = sequenceNumber, fields = fields, rawContent = line, errors = errors)
    }

    private fun resolveColumnIndex(fieldSpec: FieldSpec, headerIndexMap: Map<String, Int>): Int? =
        fieldSpec.columnIndex ?: fieldSpec.columnName?.let { headerIndexMap[it.lowercase()] }

    private fun splitRespectingQuotes(line: String, delimiter: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            when {
                line[i] == '"' -> inQuotes = !inQuotes
                !inQuotes && line.startsWith(delimiter, i) -> {
                    result.add(current.toString())
                    current.clear()
                    i += delimiter.length - 1
                }
                else -> current.append(line[i])
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    private fun coerceValue(raw: String?, fieldSpec: FieldSpec): Pair<Any?, ParseError?> {
        if (raw.isNullOrBlank()) {
            if (fieldSpec.required && !fieldSpec.nullable)
                return Pair(
                    fieldSpec.defaultValue,
                    ParseError(
                        field = fieldSpec.name,
                        message = "Required field '${fieldSpec.name}' is missing or empty",
                        severity = Severity.ERROR,
                        rawValue = raw
                    )
                )
            return Pair(fieldSpec.defaultValue, null)
        }

        return runCatching {
            when (fieldSpec.type) {
                FieldType.STRING, FieldType.ALPHANUMERIC -> Pair(raw, null)
                FieldType.INTEGER -> Pair(raw.trim().toInt(), null)
                FieldType.LONG -> Pair(raw.trim().toLong(), null)
                FieldType.DECIMAL, FieldType.AMOUNT -> Pair(raw.trim().toBigDecimal(), null)
                FieldType.BOOLEAN -> Pair(raw.trim().lowercase() in BOOLEAN_TRUE_VALUES, null)
                else -> Pair(raw.trim(), null)
            }
        }.getOrElse {
            Pair(
                raw,
                ParseError(
                    field = fieldSpec.name,
                    message = "Cannot convert '${fieldSpec.name}' value '${if (fieldSpec.sensitive) "***" else raw}' to ${fieldSpec.type}",
                    severity = Severity.ERROR,
                    rawValue = if (fieldSpec.sensitive) null else raw
                )
            )
        }
    }
}
