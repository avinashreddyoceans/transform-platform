package com.transformplatform.common.domain.action

import java.time.Duration
import java.util.UUID

// ── StepType ──────────────────────────────────────────────────────────────────
//
// Every WorkflowStep has a type that tells the orchestrator which StepExecutor
// implementation to dispatch to. New step types can be added without touching
// the orchestrator — just add a new StepExecutor @Component.

enum class StepType {
    // ── Inbound / parsing ─────────────────────────────────────────────
    /** Parse an arrived file into a stream of ParsedRecords using FileParser + FileSpec. */
    PARSE_FILE,

    // ── Data quality ─────────────────────────────────────────────────
    /** Run CorrectionEngine on the current record stream. */
    CORRECT,

    /** Run ValidationEngine on the current record stream. */
    VALIDATE,

    /** Remove duplicate records from the stream using checksum comparison. */
    DEDUPLICATE,

    /** Assert the record count matches an expected value (query DB or external). */
    VALIDATE_COMPLETENESS,

    // ── Transformation ────────────────────────────────────────────────
    /** Remap fields from one schema to another using FieldMappings. */
    TRANSFORM_RECORD,

    /** Merge window events and file-parsed records into one unified stream. */
    MERGE_SOURCES,

    /** Convert a FileRecord into a notification payload using a Handlebars template. */
    MAP_TO_NOTIFICATION,

    // ── Outbound / delivery ───────────────────────────────────────────
    /** Produce an output file from the record stream using FileGenerator + FileSpec. */
    GENERATE_FILE,

    /** Upload the generated file to a remote system via IntegrationChannel. */
    DELIVER_FILE,

    /** Save window events or records to S3/MinIO as JSONL.gz for audit/replay. */
    ARCHIVE,

    /** Send a notification over a channel (HTTP webhook, Kafka, MessageQueue, etc.). */
    NOTIFY,

    /** Call an external REST endpoint with a templated body; supports retry. */
    INVOKE_EXTERNAL,
}

// ── RetryPolicy ───────────────────────────────────────────────────────────────
//
// Per-step retry configuration. Exponential backoff with a cap.
// Flink analogy: Flink's restart strategy applied at the step level rather
// than the job level — we get finer-grained control.

data class RetryPolicy(

    /** Maximum number of execution attempts (1 = no retry). Default: 3. */
    val maxAttempts: Int = 3,

    /** Delay before the first retry. Default: 1 second. */
    val initialDelay: Duration = Duration.ofSeconds(1),

    /**
     * Multiplier applied to delay after each failure.
     * delay(n) = min(initialDelay * backoffMultiplier^n, maxDelay)
     * Default: 2.0 → 1s, 2s, 4s, 8s …
     */
    val backoffMultiplier: Double = 2.0,

    /** Upper bound on retry delay. Default: 30 seconds. */
    val maxDelay: Duration = Duration.ofSeconds(30),

    /**
     * Exception class simple names that should trigger a retry.
     * If empty, all exceptions trigger retry up to [maxAttempts].
     * Example: ["IOException", "TimeoutException"]
     * Non-listed exceptions fail fast without retry.
     */
    val retryableErrors: List<String> = emptyList(),
) {
    /** Compute the delay for attempt N (0-indexed). */
    fun delayFor(attempt: Int): Duration {
        val ms = (initialDelay.toMillis() * Math.pow(backoffMultiplier, attempt.toDouble())).toLong()
        return Duration.ofMillis(minOf(ms, maxDelay.toMillis()))
    }

    companion object {
        val NO_RETRY = RetryPolicy(maxAttempts = 1)
        val DEFAULT = RetryPolicy()
        val AGGRESSIVE = RetryPolicy(maxAttempts = 5, initialDelay = Duration.ofMillis(500))
    }
}

// ── WorkflowStep ──────────────────────────────────────────────────────────────
//
// A single step inside an Action's workflow.
// Steps execute in ascending `executionOrder`.
// The output of step N becomes the input context of step N+1 via StepContext.
//
// Config is stored as a type-erased Map<String, Any> (JSONB in the DB).
// Each StepExecutor knows the expected shape of its config and reads it via
// a typed helper. This avoids per-step DB tables while keeping configs
// fully schema-flexible.

data class WorkflowStep(

    val id: String = UUID.randomUUID().toString(),

    /** Human-readable name shown in the UI and in execution logs. */
    val name: String,

    val stepType: StepType,

    /**
     * Position within the action. Steps run lowest-first.
     * Gaps are allowed (10, 20, 30) to leave room for future insertions.
     */
    val executionOrder: Int,

    /**
     * Step-specific configuration. Shape varies by [stepType]:
     *
     *   PARSE_FILE      → { fileSpecId, integrationId, filePattern }
     *   VALIDATE        → { failFast: Boolean, maxErrors: Int }
     *   MERGE_SOURCES   → { mergeKey: String, deduplicateAfterMerge: Boolean }
     *   GENERATE_FILE   → { fileSpecId, outputFormat, fileNameTemplate }
     *   DELIVER_FILE    → { integrationId, remotePathTemplate }
     *   NOTIFY          → { integrationId, batchSize: Int, bodyTemplate: String }
     *   INVOKE_EXTERNAL → { url, method, bodyTemplate, headers, timeoutMs }
     *   ARCHIVE         → { integrationId, pathTemplate }
     *   (others)        → {}  (use spec defaults)
     */
    val config: Map<String, Any> = emptyMap(),

    /** Retry behaviour for this step. Defaults to 3 attempts with exponential backoff. */
    val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,

    /** When false, this step is skipped during execution but kept in the config. */
    val enabled: Boolean = true,

) {
    /** Convenience: read a required string from config, throwing a clear error if absent. */
    fun requireConfig(key: String): String = config[key]?.toString()
        ?: error("Step '$name' ($stepType) is missing required config key '$key'")

    fun optionalConfig(key: String): String? = config[key]?.toString()

    fun configBool(key: String, default: Boolean = false): Boolean = config[key]?.toString()?.toBooleanStrictOrNull() ?: default

    fun configInt(key: String, default: Int = 0): Int = config[key]?.toString()?.toIntOrNull() ?: default
}
