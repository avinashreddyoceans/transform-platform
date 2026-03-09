package com.transformplatform.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter

/**
 * Global CORS configuration.
 *
 * Allows the Docusaurus API docs ("Try It" / "Send API Request" panel) and local
 * development tools to call this service directly from the browser.
 *
 * Origins allowed:
 *   - http://localhost:3000   — Docusaurus dev server (`npm start`)
 *   - http://localhost:3001   — Docusaurus dev server (alternate port)
 *   - https://avinashreddyoceans.github.io  — GitHub Pages production docs
 *
 * All /api/v1/ and /actuator paths are covered.
 * Only the HTTP methods actually used by the controllers are listed.
 **/


@Configuration
class CorsConfig {

    @Bean
    fun corsFilter(): CorsFilter {
        val config = CorsConfiguration().apply {
            allowedOrigins = listOf(
                "http://localhost:3000",
                "http://localhost:3001",
                "https://avinashreddyoceans.github.io",
            )
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf(
                "Content-Type",
                "Accept",
                "Authorization",
                "X-Requested-With",
                "X-Correlation-Id",
            )
            exposedHeaders = listOf(
                "X-Correlation-Id",
                "Location",
            )
            allowCredentials = false
            maxAge = 3600L // pre-flight cache: 1 hour
        }

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/api/**", config)
        source.registerCorsConfiguration("/actuator/**", config)

        return CorsFilter(source)
    }
}