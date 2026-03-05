---
id: testing
title: Testing Requirements
sidebar_position: 2
---

# Testing Requirements

## Framework: Kotest Only

The project uses **Kotest** exclusively. JUnit Vintage is excluded from the test classpath. Never create JUnit test classes.

## Spec Style Guide

Use the correct Kotest spec style for what you're testing:

| What you're testing | Kotest style |
|---------------------|-------------|
| Parser behaviour | `DescribeSpec` |
| Correction rules | `ShouldSpec` |
| Validation rules | `BehaviorSpec` (Given/When/Then) |
| Registry / routing | `FunSpec` |
| Pipeline integration | `ShouldSpec` or `FunSpec` |

## Minimum Coverage Requirements

Every new feature needs tests:

- Every new **parser** → `DescribeSpec` covering happy path, malformed input, sensitive field masking
- Every new **writer** → `FunSpec` covering `supports()`, `write()`, `flush()`
- Every new **correction type** → `ShouldSpec` cases in `CorrectionEngineTest`
- Every new **validation rule type** → `BehaviorSpec` (Given/When/Then) in `ValidationEngineTest`

## Coroutine Testing

```kotlin
// Use runTest for suspend functions
class MyTest : FunSpec({
    test("my suspend test") {
        runTest {
            val result = suspendFun()
            result shouldBe expected
        }
    }
})

// Use .toList() to collect flows
test("parser emits correct records") {
    val records = parser.parse(input, spec).toList()
    records shouldHaveSize 10
}
```

## No Spring Context in Unit Tests

- Instantiate classes directly in unit tests
- Use `MockK` for mocking: `mockk<MyDependency>()`
- Only use `@SpringBootTest` for integration tests that genuinely need the full context

## Running Tests

```bash
# All modules
./gradlew test

# Core only (fast — 53 tests)
./gradlew :platform-core:test

# With HTML report
./gradlew :platform-core:test && open platform-core/build/reports/tests/test/index.html
```

## Before Every Commit

```bash
./gradlew :platform-core:test
```

All tests must pass before opening a PR.
