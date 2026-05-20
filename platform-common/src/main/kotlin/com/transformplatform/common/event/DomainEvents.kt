package com.transformplatform.common.event

import com.transformplatform.common.domain.window.FileHandle
import com.transformplatform.common.domain.window.WindowStatus
import com.transformplatform.common.domain.workflow.WorkflowStatus
import java.time.Instant

// ── DomainEvent ───────────────────────────────────────────────────────────────
//
// All domain events extend this sealed class.
//
// Today these travel in-process via Spring ApplicationEventPublisher.
// Tomorrow, when modules split into microservices, these become the Kafka
// message schema — the sealed class hierarchy is the contract.
//
// Design rules:
//  - Immutable data classes only — no behaviour
//  - No Spring or JPA dependencies — plain Kotlin
//  - Include enough context that a consumer doesn't need to query the DB
//    to handle the event (denormalised on purpose)

sealed class DomainEvent {
    abstract val occurredAt: Instant
}

// ── Profile lifecycle ─────────────────────────────────────────────────────────

data class ProfileEnabledEvent(
    val profileId: String,
    val clientId: String,
    val profileVersion: Int,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

data class ProfileDisabledEvent(
    val profileId: String,
    val clientId: String,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

data class ProfileUpdatedEvent(
    val profileId: String,
    val clientId: String,
    val newVersion: Int,
    val previousVersion: Int,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

data class ProfileDeletedEvent(
    val profileId: String,
    val clientId: String,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

// ── Window lifecycle ──────────────────────────────────────────────────────────

data class WindowOpenedEvent(
    val windowInstanceId: String,
    val profileId: String,
    val clientId: String,
    val profileVersion: Int,
    val openedAt: Instant,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

/**
 * Fired when the close trigger resolves.
 * Consumers (e.g. platform-integration) should stop accepting new events for this window.
 */
data class WindowClosingEvent(
    val windowInstanceId: String,
    val profileId: String,
    val clientId: String,
    val eventCount: Int,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

data class WindowClosedEvent(
    val windowInstanceId: String,
    val profileId: String,
    val clientId: String,
    val finalStatus: WindowStatus,
    val eventCount: Int,
    val totalRecordsProcessed: Int,
    val openedAt: Instant?,
    val closedAt: Instant,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

data class WindowForceClosedEvent(
    val windowInstanceId: String,
    val profileId: String,
    val reason: String,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

// ── File and event ingestion ──────────────────────────────────────────────────

/**
 * Published by platform-integration when a file arrives on a watched channel.
 * Consumed by platform-scheduler to evaluate FILE_ARRIVAL triggers.
 */
data class FileArrivedEvent(
    val windowInstanceId: String,
    val profileId: String,
    val integrationId: String,
    val fileHandle: FileHandle,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

/**
 * Published by platform-integration when a raw event is received from Kafka / REST / DB.
 * Consumed by platform-scheduler to ingest into the window and evaluate EVENT_COUNT trigger.
 */
data class RawEventReceivedEvent(
    val windowInstanceId: String,
    val profileId: String,
    val integrationId: String,
    val payloadChecksum: String,
    val newEventCount: Int,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

/**
 * Published by EventIngestionService when a duplicate event is rejected.
 * Can trigger ON_DUPLICATE_DETECTED action chain (Phase 5).
 */
data class DuplicateEventRejectedEvent(
    val windowInstanceId: String,
    val profileId: String,
    val payloadChecksum: String,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

/**
 * Published when an event arrives after the window has closed.
 * Triggers handling according to WindowConfig.lateEventBehaviour.
 */
data class LateEventArrivedEvent(
    val profileId: String,
    val closedWindowInstanceId: String,
    val targetWindowInstanceId: String?, // null if routed to dead-letter
    val payloadChecksum: String,
    val arrivedAt: Instant,
    val windowClosedAt: Instant,
    val latencyMs: Long,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

// ── Workflow execution ────────────────────────────────────────────────────────

data class WorkflowStartedEvent(
    val workflowExecutionId: String,
    val windowInstanceId: String,
    val profileId: String,
    val actionId: String,
    val actionName: String,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

data class WorkflowStepCompletedEvent(
    val workflowExecutionId: String,
    val windowInstanceId: String,
    val profileId: String,
    val stepId: String,
    val stepName: String,
    val stepIndex: Int,
    val recordsProcessed: Int,
    val durationMs: Long?,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

data class WorkflowStepFailedEvent(
    val workflowExecutionId: String,
    val windowInstanceId: String,
    val profileId: String,
    val stepId: String,
    val stepName: String,
    val attempt: Int,
    val maxAttempts: Int,
    val errorMessage: String,
    val willRetry: Boolean,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

data class WorkflowCompletedEvent(
    val workflowExecutionId: String,
    val windowInstanceId: String,
    val profileId: String,
    val finalStatus: WorkflowStatus,
    val totalRecordsProcessed: Int,
    val durationMs: Long?,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()

data class WorkflowFailedEvent(
    val workflowExecutionId: String,
    val windowInstanceId: String,
    val profileId: String,
    val actionId: String,
    val failedStepName: String,
    val errorMessage: String,
    override val occurredAt: Instant = Instant.now(),
) : DomainEvent()
