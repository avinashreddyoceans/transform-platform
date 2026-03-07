package com.transformplatform.api.config

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Micrometer configuration for the Transform Platform.
 *
 * Attaches common tags to every metric so that Grafana dashboards
 * can filter by service, environment, and version without per-metric config.
 *
 * Tags added to all metrics:
 *   - service:  "transform-platform"  (matches spring.application.name)
 *   - env:      active Spring profile  (local / staging / prod)
 *   - version:  application version    (from build info or default "unknown")
 */
@Configuration
class ObservabilityConfig {

    @Value("\${spring.application.name:transform-platform}")
    private lateinit var appName: String

    @Value("\${spring.profiles.active:local}")
    private lateinit var activeProfile: String

    @Value("\${build.version:unknown}")
    private lateinit var buildVersion: String

    /**
     * Attach common tags to every Micrometer metric registered in this application.
     * These appear as label dimensions in Prometheus and Grafana.
     */
    @Bean
    fun commonTagsCustomizer(): MeterRegistryCustomizer<MeterRegistry> =
        MeterRegistryCustomizer { registry ->
            registry.config().commonTags(
                "service", appName,
                "env",     activeProfile,
                "version", buildVersion,
            )
        }
}
