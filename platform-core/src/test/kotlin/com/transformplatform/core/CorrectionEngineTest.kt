package com.transformplatform.core

import com.transformplatform.core.spec.model.CorrectionRule
import com.transformplatform.core.spec.model.CorrectionType
import com.transformplatform.core.spec.model.FieldSpec
import com.transformplatform.core.spec.model.FieldType
import com.transformplatform.core.spec.model.FileFormat
import com.transformplatform.core.spec.model.FileSpec
import com.transformplatform.core.spec.model.ParsedRecord
import com.transformplatform.core.transformers.CorrectionEngine
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Uses ShouldSpec — clean and readable for transformation rules.
 */
class CorrectionEngineTest : ShouldSpec({

    val engine = CorrectionEngine()

    fun record(vararg fields: Pair<String, Any?>) = ParsedRecord(0L, mapOf(*fields))

    fun specWithRule(field: String, type: CorrectionType, value: String? = null) = FileSpec(
        id = "corr-spec",
        name = "Correction Test",
        format = FileFormat.CSV,
        fields = listOf(FieldSpec(name = field, type = FieldType.STRING, columnName = field)),
        correctionRules = listOf(CorrectionRule("cr1", field, type, value)),
    )

    context("String corrections") {

        should("TRIM removes leading and trailing whitespace") {
            val result = engine.applyCorrections(record("name" to "  John  "), specWithRule("name", CorrectionType.TRIM))
            result.fields["name"] shouldBe "John"
            result.corrected.shouldBeTrue()
        }

        should("UPPERCASE converts string to upper case") {
            val result = engine.applyCorrections(record("code" to "active"), specWithRule("code", CorrectionType.UPPERCASE))
            result.fields["code"] shouldBe "ACTIVE"
        }

        should("LOWERCASE converts string to lower case") {
            val result = engine.applyCorrections(record("email" to "USER@EXAMPLE.COM"), specWithRule("email", CorrectionType.LOWERCASE))
            result.fields["email"] shouldBe "user@example.com"
        }

        should("TITLE_CASE capitalises each word") {
            val result = engine.applyCorrections(record("name" to "john doe"), specWithRule("name", CorrectionType.TITLE_CASE))
            result.fields["name"] shouldBe "John Doe"
        }

        should("REMOVE_SPECIAL_CHARS strips non-alphanumeric characters") {
            val result = engine.applyCorrections(record("ref" to "ABC-123!@#"), specWithRule("ref", CorrectionType.REMOVE_SPECIAL_CHARS))
            result.fields["ref"] shouldBe "ABC123"
        }
    }

    context("Default value corrections") {

        should("DEFAULT_IF_NULL sets value when field is null") {
            val result = engine.applyCorrections(
                record("status" to null),
                specWithRule("status", CorrectionType.DEFAULT_IF_NULL, "PENDING"),
            )
            result.fields["status"] shouldBe "PENDING"
        }

        should("DEFAULT_IF_NULL does not overwrite existing value") {
            val result = engine.applyCorrections(
                record("status" to "ACTIVE"),
                specWithRule("status", CorrectionType.DEFAULT_IF_NULL, "PENDING"),
            )
            result.fields["status"] shouldBe "ACTIVE"
        }

        should("DEFAULT_IF_EMPTY sets value when field is blank string") {
            val result = engine.applyCorrections(record("ref" to "   "), specWithRule("ref", CorrectionType.DEFAULT_IF_EMPTY, "N/A"))
            result.fields["ref"] shouldBe "N/A"
        }
    }

    context("Padding corrections") {

        should("PAD_LEFT zero-pads a number to required length") {
            val result = engine.applyCorrections(record("accountNo" to "123"), specWithRule("accountNo", CorrectionType.PAD_LEFT, "10"))
            result.fields["accountNo"] shouldBe "0000000123"
        }

        should("PAD_RIGHT space-pads a string to required length") {
            val result = engine.applyCorrections(record("name" to "BOB"), specWithRule("name", CorrectionType.PAD_RIGHT, "10"))
            result.fields["name"] shouldBe "BOB       "
        }
    }

    context("Date format coercion") {

        should("convert MM/dd/yyyy to ISO yyyy-MM-dd") {
            val result = engine.applyCorrections(
                record("txDate" to "03/15/2024"),
                specWithRule("txDate", CorrectionType.DATE_FORMAT_COERCE, "yyyy-MM-dd"),
            )
            result.fields["txDate"] shouldBe "2024-03-15"
        }

        should("convert yyyyMMdd compact format to ISO yyyy-MM-dd") {
            val result = engine.applyCorrections(
                record("txDate" to "20240315"),
                specWithRule("txDate", CorrectionType.DATE_FORMAT_COERCE, "yyyy-MM-dd"),
            )
            result.fields["txDate"] shouldBe "2024-03-15"
        }

        should("leave unrecognised date strings unchanged") {
            val result = engine.applyCorrections(
                record("txDate" to "not-a-date"),
                specWithRule("txDate", CorrectionType.DATE_FORMAT_COERCE, "yyyy-MM-dd"),
            )
            result.fields["txDate"] shouldBe "not-a-date"
        }
    }

    context("Number format coercion") {

        should("strip currency symbols and commas") {
            val result = engine.applyCorrections(
                record("amount" to "$1,234.56"),
                specWithRule("amount", CorrectionType.NUMBER_FORMAT_COERCE),
            )
            result.fields["amount"] shouldBe "1234.56"
        }
    }

    context("Regex replace") {

        should("apply regex replacement using -> separator") {
            val result = engine.applyCorrections(
                record("phone" to "123-456-7890"),
                specWithRule("phone", CorrectionType.REGEX_REPLACE, "[^0-9] -> "),
            )
            result.fields["phone"] shouldBe "1234567890"
        }
    }

    context("Correction tracking") {

        should("record corrected=true when at least one correction was applied") {
            val result = engine.applyCorrections(record("name" to "  alice  "), specWithRule("name", CorrectionType.TRIM))
            result.corrected.shouldBeTrue()
        }

        should("track applied corrections with before and after values") {
            val result = engine.applyCorrections(record("name" to "  alice  "), specWithRule("name", CorrectionType.TRIM))
            result.corrections shouldHaveSize 1
            result.corrections[0].originalValue shouldBe "  alice  "
            result.corrections[0].correctedValue shouldBe "alice"
            result.corrections[0].correctionType shouldBe CorrectionType.TRIM
        }

        should("apply multiple correction rules in applyOrder sequence") {
            val spec = FileSpec(
                id = "multi-corr",
                name = "Multi",
                format = FileFormat.CSV,
                fields = listOf(FieldSpec(name = "name", type = FieldType.STRING, columnName = "name")),
                correctionRules = listOf(
                    CorrectionRule("c1", "name", CorrectionType.TRIM, applyOrder = 1),
                    CorrectionRule("c2", "name", CorrectionType.UPPERCASE, applyOrder = 2),
                ),
            )
            val result = engine.applyCorrections(record("name" to "  alice  "), spec)
            result.fields["name"] shouldBe "ALICE"
        }
    }
})
