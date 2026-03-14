package com.transformplatform.core.spec.registry

import com.transformplatform.core.parsers.FileParser
import com.transformplatform.core.spec.model.FileFormat
import com.transformplatform.core.spec.model.FileSpec
import com.transformplatform.core.spec.model.ParsedRecord
import kotlinx.coroutines.flow.Flow
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.io.InputStream

private val log = KotlinLogging.logger {}

/**
 * Auto-discovers all FileParser beans and routes parsing to the correct one.
 *
 * To add a new format: implement FileParser, annotate with @Component.
 * Zero changes needed anywhere else. Open/Closed Principle in action.
 */
@Component
class ParserRegistry(private val parsers: List<FileParser>) {

    init {
        log.info { "ParserRegistry initialized with ${parsers.size} parsers: ${parsers.map { it.parserName }}" }
    }

    fun parse(input: InputStream, spec: FileSpec): Flow<ParsedRecord> {
        val parser = resolve(spec.format)
        log.info { "Routing format ${spec.format} to parser: ${parser.parserName}" }
        return parser.parse(input, spec)
    }

    fun resolve(format: FileFormat): FileParser {
        return parsers.firstOrNull { it.supports(format) }
            ?: throw UnsupportedFormatException(
                "No parser found for format: $format. " +
                    "Available parsers: ${parsers.map { it.parserName }}",
            )
    }

    fun supportedFormats(): List<FileFormat> = FileFormat.entries.filter { format -> parsers.any { it.supports(format) } }
}

class UnsupportedFormatException(message: String) : RuntimeException(message)
