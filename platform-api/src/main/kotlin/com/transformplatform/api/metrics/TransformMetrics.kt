package com.transformplatform.api.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class TransformMetrics(private val registry: MeterRegistry) {

    fun recordProcessed(specId: String, status: String = "processed") =
        registry.counter("transform.records.processed", "specId", specId, "status", status).increment()

    fun recordFailed(specId: String, severity: String) =
        registry.counter("transform.records.failed", "specId", specId, "severity", severity).increment()

    fun fileSubmitted(specId: String) =
        registry.counter("transform.files.submitted", "specId", specId).increment()

    fun recordFileDuration(specId: String, status: String, durationMs: Long) =
        fileDurationTimer(specId, status).record(durationMs, TimeUnit.MILLISECONDS)

    fun windowEventCollected(profileId: String) =
        registry.counter("window.events.collected", "profileId", profileId).increment()

    fun windowEventDuplicate(profileId: String) =
        registry.counter("window.events.duplicates", "profileId", profileId).increment()

    fun windowOpened(profileId: String) =
        registry.counter("window.open.count", "profileId", profileId).increment()

    fun windowClosed(profileId: String, status: String) =
        registry.counter("window.close.count", "profileId", profileId, "status", status).increment()

    fun recordWindowDuration(profileId: String, status: String, durationMs: Long) =
        windowDurationTimer(profileId, status).record(durationMs, TimeUnit.MILLISECONDS)

    fun recordActionDuration(profileId: String, actionType: String, status: String, durationMs: Long) =
        actionDurationTimer(profileId, actionType, status).record(durationMs, TimeUnit.MILLISECONDS)

    fun actionChainFailed(profileId: String, condition: String) =
        registry.counter("action.chain.failures", "profileId", profileId, "condition", condition).increment()

    fun integrationFilePickedUp(integrationId: String, channelType: String) =
        registry.counter("integration.files.picked_up", "integrationId", integrationId, "channelType", channelType).increment()

    fun integrationFileDelivered(integrationId: String, channelType: String, status: String) =
        registry.counter("integration.files.delivered", "integrationId", integrationId, "channelType", channelType, "status", status).increment()

    private fun fileDurationTimer(specId: String, status: String): Timer =
        Timer.builder("transform.file.duration")
            .description("End-to-end duration of a file transformation")
            .tag("specId", specId)
            .tag("status", status)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry)

    private fun windowDurationTimer(profileId: String, status: String): Timer =
        Timer.builder("window.lifecycle.duration")
            .description("Full lifecycle duration of a window (open to close)")
            .tag("profileId", profileId)
            .tag("status", status)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry)

    private fun actionDurationTimer(profileId: String, actionType: String, status: String): Timer =
        Timer.builder("action.execution.duration")
            .description("Execution duration of a single action in an action chain")
            .tag("profileId", profileId)
            .tag("actionType", actionType)
            .tag("status", status)
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry)
}
