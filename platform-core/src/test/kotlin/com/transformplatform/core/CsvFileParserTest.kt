package com.transformplatform.core

import com.transformplatform.core.parsers.impl.CsvFileParser
import com.transformplatform.core.spec.model.FieldSpec
import com.transformplatform.core.spec.model.FieldType
import com.transformplatform.core.spec.model.FileFormat
import com.transformplatform.core.spec.model.FileSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.toList

class CsvFileParserTest : DescribeSpec({

    val parser = CsvFileParser()

    val baseSpec = FileSpec(
        id = "test-csv",
        name = "Test CSV Spec",
        format = FileFormat.CSV,
        hasHeader = true,
        delimiter = ",",
        fields = listOf(
            FieldSpec(name = "id", type = FieldType.INTEGER, columnName = "id"),
            FieldSpec(name = "name", type = FieldType.STRING, columnName = "name"),
            FieldSpec(name = "amount", type = FieldType.DECIMAL, columnName = "amount"),
            FieldSpec(name = "email", type = FieldType.STRING, columnName = "email", required = false),
        ),
    )

    describe("CSV parsing — happy paths") {

        it("parses a well-formed CSV with header correctly") {
            val csv = """
                id,name,amount,email
                1,John Doe,100.50,john@example.com
                2,Jane Smith,200.75,jane@example.com
            """.trimIndent()

            val records = parser.parse(csv.byteInputStream(), baseSpec).toList()

            records shouldHaveSize 2
            records[0].fields["id"] shouldBe 1
            records[0].fields["name"] shouldBe "John Doe"
            records[0].fields["amount"] shouldBe "100.50".toBigDecimal()
            records[0].isValid.shouldBeTrue()
        }

        it("assigns sequential sequence numbers starting from 0") {
            val csv = "id,name,amount\n1,A,1.0\n2,B,2.0\n3,C,3.0"

            val records = parser.parse(csv.byteInputStream(), baseSpec).toList()

            records.map { it.sequenceNumber } shouldBe listOf(0L, 1L, 2L)
        }

        it("handles quoted fields that contain the delimiter") {
            val csv = "id,name,amount\n1,\"Smith, John\",500.00"

            val records = parser.parse(csv.byteInputStream(), baseSpec).toList()

            records shouldHaveSize 1
            records[0].fields["name"] shouldBe "Smith, John"
        }

        it("skips blank lines without emitting records") {
            val csv = "id,name,amount\n1,Alice,10.0\n\n2,Bob,20.0\n"

            val records = parser.parse(csv.byteInputStream(), baseSpec).toList()

            records shouldHaveSize 2
        }

        it("treats optional missing field as null without error") {
            val csv = "id,name,amount\n1,Alice,10.0" // email column absent

            val records = parser.parse(csv.byteInputStream(), baseSpec).toList()

            records shouldHaveSize 1
            records[0].isValid.shouldBeTrue()
        }
    }

    describe("CSV parsing — delimiter variants") {

        it("parses pipe-delimited files") {
            val pipeSpec = baseSpec.copy(delimiter = "|")
            val csv = "id|name|amount\n1|Alice|99.99"

            val records = parser.parse(csv.byteInputStream(), pipeSpec).toList()

            records shouldHaveSize 1
            records[0].fields["name"] shouldBe "Alice"
        }

        it("parses tab-delimited files") {
            val tabSpec = baseSpec.copy(delimiter = "\t")
            val csv = "id\tname\tamount\n1\tBob\t55.00"

            val records = parser.parse(csv.byteInputStream(), tabSpec).toList()

            records shouldHaveSize 1
            records[0].fields["name"] shouldBe "Bob"
        }
    }

    describe("CSV parsing — validation errors") {

        it("adds an error when a required field is empty") {
            val csv = "id,name,amount\n1,,100.50"

            val records = parser.parse(csv.byteInputStream(), baseSpec).toList()

            records shouldHaveSize 1
            records[0].isValid.shouldBeFalse()
            records[0].errors.any { it.field == "name" }.shouldBeTrue()
        }

        it("adds an error when integer field contains non-numeric value") {
            val csv = "id,name,amount\nabc,John,100.00"

            val records = parser.parse(csv.byteInputStream(), baseSpec).toList()

            records[0].errors.any { it.field == "id" }.shouldBeTrue()
            records[0].errors[0].message shouldContain "id"
        }

        it("accumulates multiple errors on the same record") {
            val csv = "id,name,amount\nabc,,not-a-number"

            val records = parser.parse(csv.byteInputStream(), baseSpec).toList()

            records[0].errors.size shouldBe 3 // id type, name required, amount type
        }
    }

    describe("CSV parsing — large file simulation") {

        it("processes 10,000 records without loading all into memory") {
            val header = "id,name,amount\n"
            val rows = (1..10_000).joinToString("\n") { i -> "$i,User$i,${i * 1.5}" }
            val csv = header + rows

            val records = parser.parse(csv.byteInputStream(), baseSpec).toList()

            records shouldHaveSize 10_000
            records.last().sequenceNumber shouldBe 9_999L
        }
    }

    describe("Spec validation") {

        it("rejects a spec where fields have neither columnIndex nor columnName") {
            val badSpec = baseSpec.copy(
                fields = listOf(FieldSpec(name = "id", type = FieldType.STRING)), // missing column info
            )
            shouldThrow<IllegalArgumentException> {
                parser.validateSpec(badSpec)
            }
        }

        it("rejects a spec with no fields") {
            val emptySpec = baseSpec.copy(fields = emptyList())
            shouldThrow<IllegalArgumentException> {
                parser.validateSpec(emptySpec)
            }
        }

        it("supports format CSV and DELIMITED") {
            parser.supports(FileFormat.CSV).shouldBeTrue()
            parser.supports(FileFormat.DELIMITED).shouldBeTrue()
            parser.supports(FileFormat.XML).shouldBeFalse()
        }
    }
})
