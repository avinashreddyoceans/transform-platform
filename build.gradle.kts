import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.22" apply false
    kotlin("plugin.spring") version "1.9.22" apply false
    kotlin("plugin.jpa") version "1.9.22" apply false
    id("org.springframework.boot") version "3.2.3" apply false
    id("io.spring.dependency-management") version "1.1.4" apply false
}

// Versions
val kotlinVersion = "1.9.22"
val springBootVersion = "3.2.3"
val kafkaVersion = "3.6.1"
val coroutinesVersion = "1.7.3"
val jacksonVersion = "2.16.1"
val kotestVersion = "5.8.1"
val mockkVersion = "1.13.9"

allprojects {
    group = "com.transformplatform"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.spring.dependency-management")

    configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
        }
    }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        val testRuntimeOnly by configurations

        // Kotlin
        implementation("org.jetbrains.kotlin:kotlin-reflect")
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")

        // Jackson
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
        implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

        // Logging
        implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")

        // ── Kotest (replaces JUnit) ──────────────────────────────────────────
        // Runner: Kotest runs on the JUnit 5 platform — no JUnit tests written
        testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
        // Core assertions — shouldBe, shouldContain, shouldThrow, etc.
        testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
        // Property-based testing — forAll, checkAll, Arb generators
        testImplementation("io.kotest:kotest-property:$kotestVersion")
        // Spring extension — allows @SpringBootTest with Kotest specs
        testImplementation("io.kotest.extensions:kotest-extensions-spring:1.1.3")
        // MockK — Kotlin-native mocking (pairs perfectly with Kotest)
        testImplementation("io.mockk:mockk:$mockkVersion")
        // Coroutines test support
        testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
        // Spring Boot Test (provides TestContext, @SpringBootTest etc.)
        testImplementation("org.springframework.boot:spring-boot-starter-test") {
            // Exclude JUnit 4 & Vintage — we use Kotest only
            exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
            exclude(group = "junit", module = "junit")
        }
    }

    tasks.withType<KotlinCompile> {
        kotlinOptions {
            freeCompilerArgs += "-Xjsr305=strict"
            jvmTarget = "21"
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()   // Kotest runner hooks into JUnit 5 platform
        // Show test output per spec
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
