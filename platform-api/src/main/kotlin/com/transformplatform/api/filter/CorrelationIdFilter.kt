package com.transformplatform.api.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Correlation ID request filter.
 *
 * Ensures every inbound HTTP request has a unique correlation ID that is:
 *   1. Read from the `X-Correlation-ID` header if the caller provides one
 *      (preserves end-to-end tracing across service boundaries)
 *   2. Generated as a UUID v4 if not present
 *   3. Set in the SLF4J MDC as `correlationId` — automatically included in
 *      every log line via log4j2-spring.xml configuration
 *   4. Propagated back to the caller in the `X-Correlation-ID` response header
 *
 * The MDC is always cleared after the request completes to prevent leakage
 * between requests on pooled threads.
 *
 * Usage in domain code — add business context to logs:
 * ```kotlin
 * MDC.put("profileId", profile.id)
 * MDC.put("windowId", instance.instanceId.toString())
 * MDC.put("specId", spec.id)
 * // ... do work ...
 * MDC.remove("profileId")
 * ```
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {

    companion object {
        const val CORRELATION_HEADER = "X-Correlation-ID"
        const val MDC_CORRELATION_KEY = "correlationId"
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val correlationId = request.getHeader(CORRELATION_HEADER)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        MDC.put(MDC_CORRELATION_KEY, correlationId)
        response.setHeader(CORRELATION_HEADER, correlationId)

        // Request entry/exit logging is handled by TracingMdcFilter (HIGHEST_PRECEDENCE + 2),
        // which runs after ServerRequestObservationFilter starts the OTel span — ensuring both
        // traceId and correlationId are present in the MDC when those log lines are emitted.
        runCatching { filterChain.doFilter(request, response) }
            .also { MDC.remove(MDC_CORRELATION_KEY) }
            .getOrThrow()
    }
}
