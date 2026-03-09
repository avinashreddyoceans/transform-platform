package com.transformplatform.api.filter

import io.micrometer.tracing.Tracer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import mu.KotlinLogging
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private val log = KotlinLogging.logger {}

/**
 * Writes the active OTel [traceId] and [spanId] into SLF4J MDC for every incoming
 * HTTP request, so that **all log statements on the request thread** include trace
 * correlation IDs in their MDC snapshot.
 *
 * ## Why a filter, not a Logback appender?
 *
 * Logback captures the MDC **snapshot at the moment a log statement executes**
 * (`LoggingEvent.getMDCPropertyMap()`). Any approach that injects into the live MDC
 * *after* that snapshot (e.g. inside `Appender.append()`) has no effect on what
 * encoders see. A Servlet filter, on the other hand, runs *before* any business code
 * on the request thread, so values it writes to MDC are present in every snapshot
 * taken during that request — exactly the same way [CorrelationIdFilter] injects
 * `correlationId`.
 *
 * ## Filter ordering
 *
 * ```
 * HIGHEST_PRECEDENCE     (-2147483648)  CorrelationIdFilter   — writes correlationId
 * HIGHEST_PRECEDENCE + 1 (-2147483647)  ServerRequestObservationFilter — starts span, opens scope
 * HIGHEST_PRECEDENCE + 2 (-2147483646)  TracingMdcFilter (this) — reads span, writes traceId/spanId
 * ...                                   Spring Security, DispatcherServlet, Controllers
 * ```
 *
 * Running at `HIGHEST_PRECEDENCE + 2` guarantees this filter executes **inside**
 * the observation scope that `ServerRequestObservationFilter` opens — so
 * [Tracer.currentSpan] returns the active span for the current request.
 *
 * ## Non-HTTP threads
 *
 * Kafka consumers, scheduled tasks, and other non-Servlet threads are not covered by
 * this filter. For those, the [com.transformplatform.api.logging.OtelTracingAppender]
 * enriches the log event via the OTel thread-local context at appender time.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class TracingMdcFilter(private val tracer: Tracer) : OncePerRequestFilter() {

    companion object {
        const val TRACE_ID_KEY = "traceId"
        const val SPAN_ID_KEY  = "spanId"
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val span = tracer.currentSpan()
        val hasTrace = span != null && span.context().traceId().isNotBlank()

        if (hasTrace) {
            MDC.put(TRACE_ID_KEY, span!!.context().traceId())
            MDC.put(SPAN_ID_KEY,  span.context().spanId())
        }

        // Log entry/exit here so both traceId (just written above) and correlationId
        // (written by CorrelationIdFilter at HIGHEST_PRECEDENCE) are in the MDC snapshot.
        log.debug { "--> ${request.method} ${request.requestURI}" }

        try {
            filterChain.doFilter(request, response)
        } finally {
            log.debug { "<-- ${request.method} ${request.requestURI} ${response.status}" }
            if (hasTrace) {
                MDC.remove(TRACE_ID_KEY)
                MDC.remove(SPAN_ID_KEY)
            }
        }
    }
}
