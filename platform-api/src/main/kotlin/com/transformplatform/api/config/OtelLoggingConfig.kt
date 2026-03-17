package com.transformplatform.api.config

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.instrumentation.log4j.appender.v2_17.OpenTelemetryAppender
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Wires the Log4j2 OpenTelemetryAppender to an OTLP HTTP log exporter.
 *
 * Configuration is driven entirely by otel.* properties in application.yml —
 * no hard-coded endpoints or resource attributes. The same YAML keys used by
 * the OTel Java SDK autoconfigure module are reused here for consistency:
 *
 *   otel.exporter.otlp.logs.endpoint     → collector HTTP endpoint for logs
 *   otel.resource.attributes.service.*   → OTel resource labels on every log record
 *   otel.resource.attributes.deployment.environment
 *
 * NOTE: opentelemetry-spring-boot-starter is intentionally NOT used because it
 * registers LogbackAppenderApplicationListener via spring.factories and Spring
 * instantiates it eagerly at startup — before any @ConditionalOnClass can guard
 * it — causing NoClassDefFoundError on ch.qos.logback when Logback is excluded.
 */
@Configuration
class OtelLoggingConfig(
    @Value("\${otel.exporter.otlp.logs.endpoint:http://localhost:4318/v1/logs}")
    private val logsEndpoint: String,

    @Value("\${otel.resource.attributes.service.name:\${spring.application.name:transform-platform}}")
    private val serviceName: String,

    @Value("\${otel.resource.attributes.service.version:\${build.version:unknown}}")
    private val serviceVersion: String,

    @Value("\${otel.resource.attributes.service.instance.id:\${HOSTNAME:localhost}}")
    private val serviceInstanceId: String,

    @Value("\${otel.resource.attributes.deployment.environment:\${spring.profiles.active:local}}")
    private val deploymentEnv: String,
) {
    @Bean
    fun openTelemetryAppenderInstaller(): ApplicationRunner =
        ApplicationRunner { OpenTelemetryAppender.install(buildOpenTelemetry()) }

    // ── Builder pipeline ──────────────────────────────────────────────────────

    private fun buildOpenTelemetry(): OpenTelemetrySdk =
        OpenTelemetrySdk.builder()
            .setLoggerProvider(buildLoggerProvider())
            .build()

    private fun buildLoggerProvider(): SdkLoggerProvider =
        SdkLoggerProvider.builder()
            .setResource(buildResource())
            .addLogRecordProcessor(BatchLogRecordProcessor.builder(buildExporter()).build())
            .build()

    private fun buildResource(): Resource =
        // AttributeKey.stringKey() avoids a dep on opentelemetry-semconv,
        // which is not part of the opentelemetry-bom in Spring Boot 3.2.x.
        Resource.getDefault().toBuilder()
            .put(AttributeKey.stringKey("service.name"), serviceName)
            .put(AttributeKey.stringKey("service.version"), serviceVersion)
            .put(AttributeKey.stringKey("service.instance.id"), serviceInstanceId)
            .put(AttributeKey.stringKey("deployment.environment"), deploymentEnv)
            .build()

    private fun buildExporter(): OtlpHttpLogRecordExporter =
        OtlpHttpLogRecordExporter.builder()
            .setEndpoint(logsEndpoint)
            .build()
}
