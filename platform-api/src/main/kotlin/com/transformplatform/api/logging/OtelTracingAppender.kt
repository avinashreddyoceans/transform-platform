package com.transformplatform.api.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.UnsynchronizedAppenderBase
import ch.qos.logback.core.spi.AppenderAttachable
import ch.qos.logback.core.spi.AppenderAttachableImpl
import io.opentelemetry.api.trace.Span

/**
 * Logback appender that bridges the active OTel trace context into the log event's
 * MDC snapshot so that [traceId] and [spanId] appear in every log event.
 *
 * ## Why the "inject into live MDC" approach doesn't work
 *
 * [LogstashEncoder] and Logback's [ch.qos.logback.classic.pattern.MDCConverter] (used
 * by PatternEncoder for `%X{traceId}`) both read from **[ILoggingEvent.getMDCPropertyMap]**
 * — the immutable MDC snapshot captured at the instant the log statement executes (i.e.
 * when the [ch.qos.logback.classic.Logger] calls `buildLoggingEventAndAppend`).
 *
 * A wrapping appender's `append()` method is called *after* that snapshot has already
 * been taken, so calling `MDC.put(...)` inside `append()` has no effect on what the
 * encoders see — they already have the old snapshot.
 *
 * ## The fix — MdcEnrichedLoggingEvent
 *
 * Instead of mutating the live MDC, this appender wraps the original [ILoggingEvent]
 * in a [MdcEnrichedLoggingEvent] that overrides [ILoggingEvent.getMDCPropertyMap] to
 * return a new map that contains the original MDC entries **plus** the OTel IDs. The
 * wrapped event is what the delegate appenders (and their encoders) see.
 *
 * Signal flow:
 * ```
 * Logger.info(...)
 *   → MDC snapshot captured                 ← too early for live MDC injection
 *   → OtelTracingAppender.append(event)
 *       → read Span.current() from OTel thread-local
 *       → wrap event in MdcEnrichedLoggingEvent(event, snapshot + traceId + spanId)
 *       → delegate.appendLoopOnAppenders(enrichedEvent)
 *           → LogstashEncoder reads enrichedEvent.mdcPropertyMap  ← sees traceId/spanId
 * ```
 *
 * MDC is ThreadLocal, so there is no cross-thread contamination. The wrapper object is
 * short-lived and GC-eligible as soon as the delegate appenders finish.
 *
 * ## logback-spring.xml usage
 *
 * ```xml
 * <appender name="JSON_CONSOLE" class="com.transformplatform.api.logging.OtelTracingAppender">
 *     <appender-ref ref="JSON_CONSOLE_RAW"/>
 * </appender>
 * ```
 *
 * No extra dependencies are required — `io.opentelemetry:opentelemetry-api` is already
 * on the classpath transitively via `opentelemetry-exporter-otlp` and
 * `micrometer-tracing-bridge-otel`.
 */
class OtelTracingAppender :
    UnsynchronizedAppenderBase<ILoggingEvent>(),
    AppenderAttachable<ILoggingEvent> {

    private val aai = AppenderAttachableImpl<ILoggingEvent>()

    override fun append(event: ILoggingEvent) {
        val spanContext = Span.current().spanContext

        val eventToDelegate = if (spanContext.isValid) {
            // Build an enriched MDC map: start with whatever the event already captured,
            // then add traceId / spanId without overwriting values set by business code.
            val enriched = HashMap<String, String>(event.mdcPropertyMap ?: emptyMap())
            enriched.putIfAbsent(TRACE_ID_KEY, spanContext.traceId)
            enriched.putIfAbsent(SPAN_ID_KEY,  spanContext.spanId)
            MdcEnrichedLoggingEvent(event, enriched)
        } else {
            event
        }

        aai.appendLoopOnAppenders(eventToDelegate)
    }

    override fun stop() {
        aai.detachAndStopAllAppenders()
        super.stop()
    }

    // ── AppenderAttachable delegation ────────────────────────────────────────

    override fun addAppender(a: Appender<ILoggingEvent>?)        = aai.addAppender(a)
    override fun iteratorForAppenders()                          = aai.iteratorForAppenders()
    override fun getAppender(name: String?)                      = aai.getAppender(name)
    override fun isAttached(a: Appender<ILoggingEvent>?)         = aai.isAttached(a)
    override fun detachAndStopAllAppenders()                     = aai.detachAndStopAllAppenders()
    override fun detachAppender(a: Appender<ILoggingEvent>?)     = aai.detachAppender(a)
    override fun detachAppender(name: String?)                   = aai.detachAppender(name)

    companion object {
        const val TRACE_ID_KEY = "traceId"
        const val SPAN_ID_KEY  = "spanId"
    }

    // ── Inner class: ILoggingEvent wrapper that overrides the MDC snapshot ───

    /**
     * A thin wrapper around an [ILoggingEvent] that returns [enrichedMdc] from
     * [getMDCPropertyMap] instead of the original snapshot. Every other method
     * delegates to the wrapped event via Kotlin's `by` delegation.
     *
     * This is the key trick: both [net.logstash.logback.encoder.LogstashEncoder] and
     * [ch.qos.logback.classic.pattern.MDCConverter] call [getMDCPropertyMap] on the
     * event they receive — so by overriding that method here, we ensure they see
     * `traceId` and `spanId` without any live-MDC mutation.
     */
    private class MdcEnrichedLoggingEvent(
        private val delegate: ILoggingEvent,
        private val enrichedMdc: Map<String, String>,
    ) : ILoggingEvent by delegate {

        override fun getMDCPropertyMap(): Map<String, String> = enrichedMdc

        @Suppress("DEPRECATION")
        override fun getMdc(): Map<String, String> = enrichedMdc
    }
}
