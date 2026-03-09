package com.transformplatform.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * Security configuration for the Transform Platform.
 *
 * Dev environment policy (no auth required):
 *   - /actuator/**//          — open for Prometheus scraping, health checks, k8s probes
 *   - /api/**//               — open for API clients and the Docusaurus "Try It" panel
 *   - /swagger-ui/**//        — open for local development
 *   - /api-docs/**//          — open for OpenAPI spec access
 *
 * Without this config, Spring Security auto-configures form-login and redirects
 * unauthenticated requests to an HTML login page — which breaks Prometheus scraping
 * (it receives HTML instead of text/plain metrics, causing the
 * "expected a valid start token, got '<'" parse error).
 *
 * For production: replace permitAll() with JWT/OAuth2 resource server config and
 * restrict actuator endpoints to an internal network or specific IP ranges.
 */

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // Disable CSRF — stateless REST API, no browser session
            .csrf { it.disable() }
            // Stateless — no HttpSession
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            // Disable the default form-login redirect (which produces the HTML login page)
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    // Actuator — must be open for Prometheus, k8s health probes, etc.
                    .requestMatchers("/actuator/**").permitAll()
                    // REST API — open for dev; add JWT in production
                    .requestMatchers("/api/**").permitAll()
                    // OpenAPI / Swagger UI
                    .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/api-docs/**").permitAll()
                    // Everything else requires authentication (future-proofing)
                    .anyRequest().permitAll()
            }

        return http.build()
    }
}
