apply(plugin = "org.jetbrains.kotlin.plugin.spring")
apply(plugin = "org.jetbrains.kotlin.plugin.jpa")

// Camel BOM is imported in the root build.gradle.kts for all subprojects.

dependencies {
    implementation(project(":platform-common"))

    // ── Spring Boot ──────────────────────────────────────────────────────────
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    // Spring Security Crypto for credential encryption (AES-256)
    implementation("org.springframework.security:spring-security-crypto")
    // Bouncy Castle for Spring Security's advanced AES support
    implementation("org.bouncycastle:bcprov-jdk18on:1.77")

    // ── Apache Camel Spring Boot ─────────────────────────────────────────────
    // Core Camel auto-configuration + Spring Boot lifecycle integration
    implementation("org.apache.camel.springboot:camel-spring-boot-starter")
    // FTP/SFTP polling consumer (camel-ftp covers both protocols)
    implementation("org.apache.camel.springboot:camel-ftp-starter")
    // AWS S3 polling consumer (v2 SDK-backed)
    implementation("org.apache.camel.springboot:camel-aws2-s3-starter")

    // ── AWS SDK (for S3 credentials / client) ────────────────────────────────
    implementation("software.amazon.awssdk:s3:2.25.16")
    implementation("software.amazon.awssdk:auth:2.25.16")

    // ── MinIO Java Client ────────────────────────────────────────────────────
    // Used to upload downloaded files to the internal MinIO backup store
    implementation("io.minio:minio:8.5.9")

    // ── Apache Commons NET (transitive via camel-ftp, explicit for SFTP JSch) ─
    implementation("com.jcraft:jsch:0.1.55")

    // ── Database ─────────────────────────────────────────────────────────────
    runtimeOnly("org.postgresql:postgresql")

    // ── Jackson ──────────────────────────────────────────────────────────────
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // ── Test ─────────────────────────────────────────────────────────────────
    // H2 for fast in-memory JPA tests (no Docker needed)
    testRuntimeOnly("com.h2database:h2")
    // Spring Boot test slice support
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
        exclude(group = "junit",             module = "junit")
    }
}
