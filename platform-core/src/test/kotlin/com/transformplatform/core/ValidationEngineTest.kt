package com.transformplatform.core

import com.transformplatform.core.spec.model.*
import com.transformplatform.core.validators.ValidationEngine
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Uses BehaviorSpec (Given / When / Then) — best for business rule tests
 * that map directly to AC (acceptance criteria).
 */
class ValidationEngineTest : BehaviorSpec({

    val engine = ValidationEngine()

    fun record(vararg fields: Pair<String, Any?>) = ParsedRecord(
        sequenceNumber = 0L,
        fields = mapOf(*fields)
    )

    fun spec(vararg rules: ValidationRule) = FileSpec(
        id = "val-spec",
        name = "Validation Test",
        format = FileFormat.CSV,
        fields = emptyList(),
        validationRules = rules.toList()
    )

    given("a NOT_NULL rule") {
        val rule = ValidationRule("r1", "amount", RuleType.NOT_NULL, message = "Amount is required")

        `when`("field value is present") {
            val result = engine.validate(record("amount" to "100"), spec(rule))
            then("record should be valid") {
                result.isValid.shouldBeTrue()
                result.errors.shouldBeEmpty()
            }
        }

        `when`("field value is null") {
            val result = engine.validate(record("amount" to null), spec(rule))
            then("record should have an error for that field") {
                result.isValid.shouldBeFalse()
                result.errors shouldHaveSize 1
                result.errors[0].field shouldBe "amount"
                result.errors[0].message shouldBe "Amount is required"
            }
        }
    }

    given("a MIN_VALUE rule of 0") {
        val rule = ValidationRule("r2", "amount", RuleType.MIN_VALUE, value = "0", message = "Amount must be non-negative")

        `when`("amount is positive") {
            val result = engine.validate(record("amount" to "500"), spec(rule))
            then("record is valid") { result.isValid.shouldBeTrue() }
        }

        `when`("amount is zero") {
            val result = engine.validate(record("amount" to "0"), spec(rule))
            then("record is valid") { result.isValid.shouldBeTrue() }
        }

        `when`("amount is negative") {
            val result = engine.validate(record("amount" to "-1"), spec(rule))
            then("record has a validation error") {
                result.isValid.shouldBeFalse()
                result.errors[0].field shouldBe "amount"
            }
        }
    }

    given("a REGEX rule for routing number") {
        val rule = ValidationRule(
            ruleId = "r3",
            field = "routingNumber",
            ruleType = RuleType.REGEX,
            value = "^[0-9]{9}$",
            message = "Routing number must be exactly 9 digits"
        )

        `when`("routing number is valid 9 digits") {
            val result = engine.validate(record("routingNumber" to "021000021"), spec(rule))
            then("record is valid") { result.isValid.shouldBeTrue() }
        }

        `when`("routing number has letters") {
            val result = engine.validate(record("routingNumber" to "02100002X"), spec(rule))
            then("record has a validation error") { result.isValid.shouldBeFalse() }
        }

        `when`("routing number is too short") {
            val result = engine.validate(record("routingNumber" to "12345"), spec(rule))
            then("record has a validation error") { result.isValid.shouldBeFalse() }
        }
    }

    given("an ALLOWED_VALUES rule") {
        val rule = ValidationRule(
            ruleId = "r4",
            field = "status",
            ruleType = RuleType.ALLOWED_VALUES,
            value = "ACTIVE,INACTIVE,PENDING",
            message = "Status must be ACTIVE, INACTIVE or PENDING"
        )

        `when`("value is in the allowed list") {
            listOf("ACTIVE", "INACTIVE", "PENDING").forEach { status ->
                val result = engine.validate(record("status" to status), spec(rule))
                then("'$status' is valid") { result.isValid.shouldBeTrue() }
            }
        }

        `when`("value is not in the allowed list") {
            val result = engine.validate(record("status" to "UNKNOWN"), spec(rule))
            then("record has a validation error") { result.isValid.shouldBeFalse() }
        }
    }

    given("a WARNING severity rule") {
        val rule = ValidationRule(
            ruleId = "r5",
            field = "description",
            ruleType = RuleType.MAX_LENGTH,
            value = "100",
            message = "Description is long",
            severity = Severity.WARNING
        )

        `when`("description exceeds max length") {
            val longDesc = "x".repeat(150)
            val result = engine.validate(record("description" to longDesc), spec(rule))
            then("record is still valid — warnings do not fail validation") {
                result.isValid.shouldBeTrue()
                result.warnings shouldHaveSize 1
                result.errors.shouldBeEmpty()
            }
        }
    }

    given("multiple rules on the same record") {
        val rules = arrayOf(
            ValidationRule("r1", "id",     RuleType.NOT_NULL, message = "ID required"),
            ValidationRule("r2", "amount", RuleType.MIN_VALUE, value = "0", message = "Amount must be >= 0"),
            ValidationRule("r3", "code",   RuleType.REGEX, value = "^[A-Z]{3}$", message = "Code must be 3 uppercase letters")
        )

        `when`("all fields are valid") {
            val result = engine.validate(record("id" to "1", "amount" to "100", "code" to "ABC"), spec(*rules))
            then("record is valid with no errors") {
                result.isValid.shouldBeTrue()
                result.errors.shouldBeEmpty()
            }
        }

        `when`("multiple fields fail") {
            val result = engine.validate(record("id" to null, "amount" to "-5", "code" to "abc"), spec(*rules))
            then("all errors are collected — pipeline does not stop at first failure") {
                result.errors shouldHaveSize 3
            }
        }
    }
})
