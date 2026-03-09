# Contributing Guidelines

## GitHub and Coding Standards

Follows [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html).

[Commit messages communicate context of a change](https://cbea.ms/git-commit/). Writing useful commit messages is
important.

[When to mark a PR conversation as resolved](https://github.com/distributed-system-analysis/pbench/discussions/2113)
is an important question. Sometimes this is not clear. Generally resolving should be left for the reviewer.

---

## Code Quality Checklist

As the author please make your code is ready for review by addressing any of the issues in the list below.

### Naming

[Return type must be specified unless it is Unit](https://kotlinlang.org/docs/coding-conventions.html#unit-return-type)

Names should be intention-revealing:

- Why does it exist in the program?
- What does it do in the program?
- How is it being used in the program?

Do not use magic numbers: e.g. `name.take(140)`. Assign an intention-revealing constant: e.g. `name.take(MAX_TWEET_LENGTH)`.

---

### Functions and Classes

Wrap long lines according to [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
for [functions](https://kotlinlang.org/docs/coding-conventions.html#functions)
or [classes](https://kotlinlang.org/docs/coding-conventions.html#class-headers).

Separation of concerns: Functions and classes should be small and focus on one thing. e.g. If your `getFromDatabase()`
function initializes the query before executing the query, you are not properly separating concerns. Create a new
function to construct the query. Call this function before executing the query.

[Prefer using `if` for binary conditions instead of `when`.](https://kotlinlang.org/docs/coding-conventions.html#if-versus-when)

[Prefer using an expression body for functions with the body consisting of a single expression](https://kotlinlang.org/docs/coding-conventions.html#functions)

Avoid single-use `val`, inline if practical. Reduce code surface area and make it easier to read. Use a functional pattern.

---

### State and Mutability

Avoid mutable state. Use functional patterns. Do not use intermediate collections. Do not use mutable collections as a
crutch to accommodate imperative programming patterns.

Avoid `var` usage. If you are using `var` you are probably doing it wrong. There are rare valid exceptions.

Use `buildList { }`, `buildMap { }`, `buildSet { }` instead of creating a `mutableListOf()` and imperatively adding to it:

```kotlin
// Wrong
val errors = mutableListOf<String>()
if (condition) errors.add("message")
return errors

// Right
return buildList {
    if (condition) add("message")
}
```

Use `fold` for functional accumulation instead of `var` + mutation in a loop:

```kotlin
// Wrong
var result = record
rules.forEach { rule ->
    result = result.withError(checkRule(rule))
}
return result

// Right
return rules.fold(record) { acc, rule -> acc.withError(checkRule(rule)) }
```

---

### Error Handling

Prefer `runCatching` over `try/catch`. It is a richer API that composes well with other Kotlin idioms:

```kotlin
// Wrong
return try {
    riskyOperation()
} catch (e: SomeException) {
    handleError(e)
}

// Right — single recoverable value
return runCatching { riskyOperation() }.getOrElse { handleError(it) }

// Right — nullable on failure
return runCatching { riskyOperation() }.getOrNull()

// Right — success/failure branches (replaces try/catch with two different outcomes)
return runCatching { riskyOperation() }.fold(
    onSuccess = { result -> buildSuccess(result) },
    onFailure = { e -> log.error(e) { "..." }; buildFailure() }
)

// Right — replaces try/finally (MDC cleanup, resource release, etc.)
runCatching { doWork() }
    .also { cleanup() }   // runs regardless of success or failure
    .getOrThrow()         // re-throws any exception after cleanup
```

Do not use `!!` non-null assertion operator unless you have a very strong reason. A `NullPointerException` provides very
little information for troubleshooting. Instead, handle the null case and throw a proper exception with a message:

```kotlin
// Wrong
val start = fieldSpec.startPosition!!

// Right
val start = requireNotNull(fieldSpec.startPosition) {
    "startPosition must be set for fixed-width field '${fieldSpec.name}'"
}
```

---

### Type Safety

Do not sacrifice type safety by converting enum values to `String` prior to passing or comparison:

```kotlin
// Wrong — string comparison loses type safety
filter { it.format.name == format.uppercase() }

// Right — compare enum values directly
filter { it.format == FileFormat.valueOf(format.uppercase()) }
```

Converting `enum class` elements to string in general should be done with `name` instead of `toString()`. The former is
final and will always do what you expect. The latter may be overridden.

Do not use nullable return types when your function never returns `null`. Do not use unnecessary safe call on a non-null receiver.

---

### Kotlin Idioms — Not Java

Kotlin is not Java. Write idiomatic Kotlin:

```kotlin
// Wrong — Java style
bigDecimal1.add(bigDecimal2)
bigDecimal1.compareTo(bigDecimal2) > 0
Math.pow(10.0, scale.toDouble()).toLong()
SimpleDateFormat("yyyy-MM-dd")
public fun doSomething()

// Right — Kotlin style
bigDecimal1 + bigDecimal2
bigDecimal1 > bigDecimal2
10.0.toBigDecimal().pow(scale)
DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
fun doSomething()
```

Prefer `DateTimeFormatter` (from `java.time`) over `SimpleDateFormat`. `SimpleDateFormat` is mutable,
not thread-safe, and locale-sensitive. Always pass an explicit locale — use `Locale.ENGLISH` for date
parsing/formatting to avoid locale-specific surprises:

```kotlin
// Wrong
SimpleDateFormat("yyyy-MM-dd")                       // mutable, not thread-safe
SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())  // locale-dependent output

// Right
DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
```

Use `setOf` instead of `listOf` for membership checks — O(1) lookup versus O(n):

```kotlin
// Wrong — O(n) list scan on every call
raw.lowercase() in listOf("true", "1", "yes", "y")

// Right — O(1) set lookup, and declared as a companion object constant
private companion object {
    val BOOLEAN_TRUE_VALUES = setOf("true", "1", "yes", "y")
}
raw.lowercase() in BOOLEAN_TRUE_VALUES
```

[Avoid redundant constructs](https://kotlinlang.org/docs/coding-conventions.html#avoid-redundant-constructs):

- Do not use redundant `this` receiver expression.
- Kotlin uses inferred typing. Do not explicitly specify type unless necessary.

---

### Performance

Avoid repeated execution of the same code. Initialize it statically in `companion object` or in the bean's `init` block:

```kotlin
// Wrong — rescans entries on every call
infix fun from(code: String): MyEnumClass? = entries.firstOrNull { it.code == code }

// Right — O(1) lookup after one-time initialization
private companion object {
    val BY_CODE = entries.associateBy { it.code }
}
infix fun from(code: String): MyEnumClass? = BY_CODE[code]
```

Pre-build expensive formatters, patterns, and parsers in `companion object`. Creating them on every call is wasteful:

```kotlin
// Wrong — new formatter created per invocation
fun format(date: LocalDate) = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH).format(date)

// Right — built once, reused across all calls
private companion object {
    val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH)
}
fun format(date: LocalDate) = ISO_DATE.format(date)
```

---

### Logging

Do not use string interpolation for log messages outside of KotlinLogging lambdas. Parametrize instead.
This is a matter of efficiency, especially for log levels `DEBUG` and `TRACE`.

With `mu.KotlinLogging`, the lambda form is idiomatic and already lazy — string construction only
happens if the log level is enabled:

```kotlin
// Wrong — string built eagerly even when log level is off
log.debug("Processing record: $sequenceNumber for spec: $specId")

// Right — lazy evaluation via lambda
log.debug { "Processing record: $sequenceNumber for spec: $specId" }
```

Remove all `println()`. Use a logger instead.

---

### Comments

In general, do not add comments to your code. That leads to two versions of the truth. Your code tells a story. If your
code is not clear, refactor until it is.

- Valid KDoc documentation is fine.
- Comments pointing out pitfalls or ambiguous conditions are acceptable.
- TODO comments are also acceptable.
- Do not add comments that simply restate what the code does — if the code needs a comment to be understood, refactor it.
- Do not duplicate documentation that already exists in annotations (e.g. `@Operation(summary = ...)` on a REST endpoint
  makes a Javadoc block above the same function redundant).

---

### Testing

Use Kotest, not JUnit.
