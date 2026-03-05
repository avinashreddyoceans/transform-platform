package com.transformplatform.core

import com.transformplatform.core.parsers.impl.CsvFileParser
import com.transformplatform.core.parsers.impl.FixedWidthFileParser
import com.transformplatform.core.parsers.impl.XmlFileParser
import com.transformplatform.core.spec.model.FileFormat
import com.transformplatform.core.spec.registry.ParserRegistry
import com.transformplatform.core.spec.registry.UnsupportedFormatException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Uses FunSpec — great for straightforward function-level tests.
 */
class ParserRegistryTest : FunSpec({

    val registry = ParserRegistry(
        listOf(CsvFileParser(), FixedWidthFileParser(), XmlFileParser())
    )

    test("resolves CSV parser for CSV format") {
        val parser = registry.resolve(FileFormat.CSV)
        parser.parserName shouldBe "CSV_PARSER"
    }

    test("resolves fixed-width parser for FIXED_WIDTH format") {
        val parser = registry.resolve(FileFormat.FIXED_WIDTH)
        parser.parserName shouldBe "FIXED_WIDTH_PARSER"
    }

    test("resolves XML parser for XML format") {
        val parser = registry.resolve(FileFormat.XML)
        parser.parserName shouldBe "XML_PARSER"
    }

    test("resolves XML parser for ISO20022 format") {
        val parser = registry.resolve(FileFormat.ISO20022)
        parser.parserName shouldBe "XML_PARSER"
    }

    test("throws UnsupportedFormatException for unregistered format") {
        // Create a registry with no parsers to simulate missing format
        val emptyRegistry = ParserRegistry(emptyList())
        val ex = shouldThrow<UnsupportedFormatException> {
            emptyRegistry.resolve(FileFormat.NACHA)
        }
        ex.message shouldContain "NACHA"
    }

    test("lists all supported formats") {
        val formats = registry.supportedFormats()
        formats shouldContainAll listOf(FileFormat.CSV, FileFormat.DELIMITED, FileFormat.FIXED_WIDTH, FileFormat.XML, FileFormat.ISO20022)
    }
})
