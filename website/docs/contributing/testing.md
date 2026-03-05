---
id: testing
title: Testing Requirements
sidebar_position: 2
---

# Testing Requirements

## Framework: Kotest Only

The project uses **Kotest** exclusively. JUnit Vintage is excluded from the test classpath. Never create JUnit test classes.

## Which Spec Style to Use

```mermaid
flowchart TD
    Q{What are you testing?}

    Q -->|Parser behaviour| DS["DescribeSpec\ndescribe block + it blocks"]
    Q -->|Correction rules| SS["ShouldSpec\ncontext block + should blocks"]
    Q -->|Validation rules| BS["BehaviorSpec\nGiven / When / Then"]
    Q -->|Registry or routing| FS["FunSpec\ntest blocks"]
    Q -->|Pipeline integration| FS2["FunSpec or ShouldSpec"]

    style DS fill:#dbeafe,stroke:#2563eb
    style SS fill:#dcfce7,stroke:#16a34a
    style BS fill:#fef9c3,stroke:#ca8a04
    style FS fill:#f3f4f6,stroke:#6b7280
    style FS2 fill:#f3f4f6,stroke:#6b7280
```

## Coverage Requirements

```mermaid
graph LR
    subgraph "New code"
        NP[New Parser]
        NW[New Writer]
        NC[New CorrectionType]
        NV[New RuleType]
    end

    subgraph "Required tests"
        DS["DescribeSpec\nhappy path + malformed input\n+ sensitive field masking"]
        FS["FunSpec\nsupports() + write() + flush()"]
        SS["ShouldSpec\nin CorrectionEngineTest"]
        BS["BehaviorSpec Given/When/Then\nin ValidationEngineTest"]
    end

    NP --> DS
    NW --> FS
    NC --> SS
    NV --> BS
```

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

## Test Isolation — No Spring Context

```mermaid
flowchart LR
    UT["Unit Tests\n(most tests)"] -->|instantiate directly| CLASS[MyParser / MyWriter / MyEngine]
    UT -->|mock dependencies| MK["MockK\nmockk()"]
    IT["Integration Tests\n(@SpringBootTest)"] -->|full context| SPRING[Spring Application Context]

    style UT fill:#dcfce7,stroke:#16a34a
    style IT fill:#fef9c3,stroke:#ca8a04
```

Never use `@SpringBootTest` in unit tests — instantiate classes directly and mock dependencies with MockK.

## Running Tests

```bash
# All modules
./gradlew test

# Core only (fast — 53 tests)
./gradlew :platform-core:test

# With HTML report
./gradlew :platform-core:test && open platform-core/build/reports/tests/test/index.html
```

:::warning
All tests must pass before opening a PR. Run `./gradlew :platform-core:test` before every commit.
:::
