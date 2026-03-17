apply(plugin = "org.springframework.boot")
apply(plugin = "org.jetbrains.kotlin.plugin.spring")
apply(plugin = "org.jetbrains.kotlin.plugin.jpa")

// ── Integration-test source set ────────────────────────────────────────────────
// Separate from unit tests: requires Docker (Testcontainers) and runs full Spring
// Boot context against real PostgreSQL + MinIO containers.
//
// Commands:
//   ./gradlew :platform-api:test              — fast unit tests (no containers)
//   ./gradlew :platform-api:integrationTest   — E2E tests (needs Docker)
//   ./gradlew :platform-api:check             — both (integrationTest dependsOn test)

sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
        kotlin.srcDirs("src/integrationTest/kotlin")
        resources.srcDirs("src/integrationTest/resources")
    }
}

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations["testImplementation"])
}
val integrationTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations["testRuntimeOnly"])
}

tasks.register<Test>("integrationTest") {
    description = "Runs end-to-end integration tests (requires Docker)"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    shouldRunAfter(tasks.named("test"))
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

tasks.named("check") {
    dependsOn(tasks.named("integrationTest"))
}

// ── Exclude Logback globally — replaced by Log4j2 ─────────────────────────────
// Spring Boot starters pull in spring-boot-starter-logging (Logback) transitively.
// Excluding both logback-classic (SLF4J binding) and logback-core keeps the classpath
// clean so Log4j2 is the sole active logger with no SLF4J multiple-binding warnings.
configurations.all {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    exclude(group = "ch.qos.logback", module = "logback-classic")
    exclude(group = "ch.qos.logback", module = "logback-core")
}

// ── Dependencies ───────────────────────────────────────────────────────────────

val testcontainersVersion: String by rootProject.extra
val springMockkVersion: String by rootProject.extra

dependencies {
    implementation(project(":platform-common"))
    implementation(project(":platform-core"))
    implementation(project(":platform-integration"))
    implementation(project(":platform-scheduler"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")

    // ── Logging — Log4j2 replaces Logback ────────────────────────────────────
    // Provides SLF4J → Log4j2 bridge + Log4j2 core. Logback excluded globally above.
    implementation("org.springframework.boot:spring-boot-starter-log4j2")
    // OTel Log4j2 appender — ships log records to OTel Collector via OTLP HTTP.
    // OtelLoggingConfig reads otel.exporter.otlp.logs.endpoint from application.yml
    // and calls OpenTelemetryAppender.install() to wire it up at startup.
    implementation("io.opentelemetry.instrumentation:opentelemetry-log4j-appender-2.17:2.4.0-alpha")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.3")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    // flyway-core includes PostgreSQL support in Flyway 9.x (Spring Boot 3.2.x).
    // flyway-database-postgresql is a Flyway 10+ module — not needed here.
    implementation("org.flywaydb:flyway-core")

    // API Docs
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")

    // ── Observability ────────────────────────────────────────────────────────
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    // OTLP exporter — used by both OtelLoggingConfig (logs via HTTP) and Spring
    // Boot's tracing autoconfiguration (traces via management.otlp.tracing.endpoint).
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // OTel SDK logs — SdkLoggerProvider + BatchLogRecordProcessor for log export.
    implementation("io.opentelemetry:opentelemetry-sdk-logs")

    // ── Unit test ────────────────────────────────────────────────────────────
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // SpringMockK — @MockkBean / @SpykBean for Spring Boot tests
    testImplementation("com.ninja-squad:springmockk:$springMockkVersion")

    // ── Integration (E2E) test ───────────────────────────────────────────────
    // Testcontainers BOM aligns all tc-* versions
    integrationTestImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    integrationTestImplementation("org.testcontainers:testcontainers")
    integrationTestImplementation("org.testcontainers:postgresql")
    integrationTestImplementation("org.testcontainers:junit-jupiter")
    // SpringMockK for @MockkBean in @SpringBootTest — mocks DynamicRouteManager
    integrationTestImplementation("com.ninja-squad:springmockk:$springMockkVersion")
    // PostgreSQL JDBC driver for Testcontainers
    integrationTestRuntimeOnly("org.postgresql:postgresql")
}
