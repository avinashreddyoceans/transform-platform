package com.transformplatform.core.parsers

import com.transformplatform.core.spec.model.FileFormat
import com.transformplatform.core.spec.model.FileSpec
import com.transformplatform.core.spec.model.ParsedRecord
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

/**
 * Contract for all file parsers.
 *
 * Parsers are format-specific but produce a universal ParsedRecord stream.
 * New formats are added by implementing this interface — no other code changes needed.
 *
 * Uses Kotlin Flow for back-pressure aware, memory-efficient streaming.
 * Files of any size are processed record-by-record, never loaded fully into memory.
 */
interface FileParser {

    /**
     * Returns true if this parser can handle the given format.
     */
    fun supports(format: FileFormat): Boolean

    /**
     * Stream-parse the input into ParsedRecords.
     * Flow is cold — parsing starts only when collected.
     * Each emission is one logical record from the file.
     */
    fun parse(input: InputStream, spec: FileSpec): Flow<ParsedRecord>

    /**
     * Validate the spec is complete and correct for this parser.
     * Called before parsing begins, throws if spec is invalid.
     */
    fun validateSpec(spec: FileSpec) {
        // default: no-op — override for format-specific spec validation
    }

    /**
     * Returns the parser name for logging and monitoring.
     */
    val parserName: String
}
