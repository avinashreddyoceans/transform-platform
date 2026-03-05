---
id: adding-correction-rules
title: Adding Correction Rules
sidebar_position: 3
---

# Adding a New Correction Rule Type

Correction rules run **before** validation. They clean, normalize, and transform field values before business rules are applied.

## Where Corrections Fit

```mermaid
flowchart LR
    P[Parse] --> C
    subgraph C["✏️ CorrectionEngine"]
        direction TB
        R1[TRIM] --> R2[UPPER_CASE] --> R3[REGEX_REPLACE] --> R4[YOUR_NEW_TYPE]
    end
    C --> V[Validate]

    style C fill:#dbeafe,stroke:#2563eb
```

## Built-in Correction Types

```mermaid
graph TD
    CT[CorrectionType enum]
    CT --> TRIM[TRIM\nstrip whitespace]
    CT --> PL[PAD_LEFT\nleft-pad to length]
    CT --> PR[PAD_RIGHT\nright-pad to length]
    CT --> UC[UPPER_CASE]
    CT --> LC[LOWER_CASE]
    CT --> RR[REGEX_REPLACE\npattern → replacement]
    CT --> CD[COERCE_DATE\nreformat date string]
    CT --> DN[DEFAULT_IF_NULL\nfill blank with default]
    CT --> NEW["YOUR_NEW_TYPE ← add here"]

    style NEW fill:#fef9c3,stroke:#ca8a04
```

## Steps

### 1. Add the enum value

In `FileSpec.kt`, add to `CorrectionType`:

```kotlin
enum class CorrectionType {
    TRIM, PAD_LEFT, PAD_RIGHT, UPPER_CASE, LOWER_CASE, REGEX_REPLACE,
    COERCE_DATE, DEFAULT_IF_NULL,
    YOUR_NEW_TYPE   // ← add here
}
```

### 2. Add the `when` branch in `CorrectionEngine`

```kotlin
fun applyCorrection(value: Any?, rule: CorrectionRule, fieldSpec: FieldSpec): Any? {
    return when (rule.correctionType) {
        CorrectionType.TRIM -> (value as? String)?.trim()
        // ... existing cases ...
        CorrectionType.YOUR_NEW_TYPE -> {
            // implement transformation
            value
        }
    }
}
```

### 3. Write tests using `ShouldSpec`

```kotlin
class CorrectionEngineTest : ShouldSpec({

    val engine = CorrectionEngine()

    context("YOUR_NEW_TYPE correction") {
        should("transform value correctly") {
            val rule = CorrectionRule(
                ruleId = "test",
                field = "amount",
                correctionType = CorrectionType.YOUR_NEW_TYPE
            )
            engine.applyCorrection("input", rule, fieldSpec) shouldBe "expected output"
        }
    }
})
```

```bash
./gradlew :platform-core:test
```

## Correction Rule Execution Order

```mermaid
flowchart LR
    subgraph spec["FileSpec.correctionRules (ordered list)"]
        R1["rule 1\nTRIM description"] --> R2["rule 2\nUPPER_CASE status"] --> R3["rule 3\nYOUR_NEW_TYPE amount"]
    end
    subgraph engine["CorrectionEngine per record"]
        direction LR
        E1[apply rule 1] --> E2[apply rule 2] --> E3[apply rule 3]
    end
    spec --> engine
```

Rules are applied in `applyOrder` — the order they appear in the `correctionRules` list in the spec.

## Checklist

- [ ] New enum value added to `CorrectionType`
- [ ] `when` branch added in `CorrectionEngine.applyCorrection()`
- [ ] `ShouldSpec` tests added to `CorrectionEngineTest`
- [ ] `AGENTS.md` §6 updated
