apply(plugin = "org.springframework.boot")
apply(plugin = "org.jetbrains.kotlin.plugin.spring")
apply(plugin = "org.jetbrains.kotlin.plugin.jpa")

// ── Frontend build integration ─────────────────────────────────────────────
//
// The platform-ui Vite project is not a Gradle subproject (to avoid Kotlin
// plugin conflicts). Instead, we exec npm from here.
//
// Development workflow:
//   cd platform-ui && npm install && npm run dev   ← hot-reload on :5173
//   (Vite proxies /api → Spring Boot :8080)
//
// Production / CI workflow:
//   ./gradlew :platform-api:bootJar               ← includes built UI assets
//   The buildFrontend task runs first and outputs to:
//     src/main/resources/static/ui/
//   Spring Boot's processResources copies these into the jar classpath.
//
// Skip the frontend build when developing backend only:
//   ./gradlew :platform-api:bootRun -PskipFrontend

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val npm = if (isWindows) listOf("cmd", "/c", "npm") else listOf("npm")
val frontendDir = file("${rootProject.projectDir}/platform-ui")
val frontendOutput = file("${projectDir}/src/main/resources/static/ui")

tasks.register<Exec>("npmInstall") {
    group = "frontend"
    description = "Installs platform-ui npm dependencies"
    workingDir = frontendDir
    commandLine = npm + listOf("install", "--prefer-offline")
    // Incremental — only re-runs if package.json changes
    inputs.file("$frontendDir/package.json")
    outputs.dir("$frontendDir/node_modules")
}

tasks.register<Exec>("buildFrontend") {
    group = "frontend"
    description = "Builds the Vite/React UI into src/main/resources/static/ui/"
    dependsOn("npmInstall")
    workingDir = frontendDir
    commandLine = npm + listOf("run", "build")
    // Incremental — only re-runs if sources change
    inputs.dir("$frontendDir/src")
    inputs.file("$frontendDir/index.html")
    inputs.file("$frontendDir/vite.config.js")
    inputs.file("$frontendDir/package.json")
    outputs.dir(frontendOutput)
    // Respect -PskipFrontend flag for backend-only dev cycles
    onlyIf { !project.hasProperty("skipFrontend") }
}

tasks.named("processResources") {
    if (!project.hasProperty("skipFrontend")) {
        dependsOn("buildFrontend")
    }
}

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

// The integrationTest source set adds src/integrationTest/resources via both the
// Gradle convention (create() defaults) and the explicit srcDirs() call below —
// dedup silently so processIntegrationTestResources never sees a duplicate entry.
tasks.named<Copy>("processIntegrationTestResources") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Test>("integrationTest") {
    description = "Runs end-to-end integration tests (requires Docker)"
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    shouldRunAfter(tasks.named("test"))
    useJUnitPlatform()
    // Required for ByteBuddy/Mockito on Java 17+ (module system restrictions)
    jvmArgs(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
    )
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
