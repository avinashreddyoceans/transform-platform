apply(plugin = "org.springframework.boot")
apply(plugin = "org.jetbrains.kotlin.plugin.spring")
apply(plugin = "org.jetbrains.kotlin.plugin.jpa")

dependencies {
    implementation(project(":platform-common"))
    implementation(project(":platform-core"))
    implementation(project(":platform-scheduler"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.3")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.3")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")

    // API Docs
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0")

    // ── Observability ────────────────────────────────────────────────────────
    // Prometheus metrics registry (exposes /actuator/prometheus)
    implementation("io.micrometer:micrometer-registry-prometheus")
    // Micrometer Tracing → OpenTelemetry bridge (Spring Boot 3.x built-in tracing)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    // OTel OTLP exporter — sends traces + metrics to OTel Collector
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // Structured JSON logging for Elasticsearch/Kibana ingestion
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
}
