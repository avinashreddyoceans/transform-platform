package com.transformplatform.core.pipeline

import com.transformplatform.core.spec.model.*
import com.transformplatform.core.spec.registry.ParserRegistry
import com.transformplatform.core.transformers.CorrectionEngine
import com.transformplatform.core.validators.ValidationEngine
import com.transformplatform.core.writers.RecordWriter
import kotlinx.coroutines.flow.*
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.io.InputStream
import java.time.Instant

private val log = KotlinLogging.logger {}

/**
 * The core pipeline orchestrator.
 *
 * Flow: InputStream → Parse → Correct → Validate → Route (valid / invalid) → Write
 *
 * Each stage is independently replaceable. Adding a new stage means
 * adding a new operator to the Flow chain — nothing else changes.
 */
@Component
class TransformationPipeline(
    private val parserRegistry: ParserRegistry,
    private val correctionEngine: CorrectionEngine,
    private val validationEngine: ValidationEngine,
    private val writers: List<RecordWriter>
) {

    suspend fun execute(request: PipelineRequest): ProcessingResult {
        val startedAt = Instant.now()
        log.info { "Pipeline starting: spec=${request.spec.id}, file=${request.fileName}, destination=${request.destination}" }

        var totalRecords = 0L
        var successfulRecords = 0L
        var failedRecords = 0L
        var correctedRecords = 0L
        var warningRecords = 0L
        val processingErrors = mutableListOf<ProcessingError>()

        val writer = resolveWriter(request.destination)

        try {
            parserRegistry.parse(request.inputStream, request.spec)
                .map { record ->
                    // Stage 1: Auto-correct
                    correctionEngine.applyCorrections(record, request.spec)
                }
                .map { record ->
                    // Stage 2: Validate
                    validationEngine.validate(record, request.spec)
                }
                .filter { record ->
                    // Stage 3: Route — skip FATAL errors, count failures
                    totalRecords++
                    when {
                        record.hasFatalErrors -> {
                            failedRecords++
                            record.errors.forEach {
                                processingErrors.add(
                                    ProcessingError(record.sequenceNumber, it.field, it.message, it.severity)
                                )
                            }
                            log.warn { "Record ${record.sequenceNumber} has FATAL errors — skipping" }
                            false
                        }
                        !record.isValid && request.skipInvalidRecords -> {
                            failedRecords++
                            false
                        }
                        else -> {
                            if (record.corrected) correctedRecords++
                            if (record.warnings.isNotEmpty()) warningRecords++
                            if (record.isValid) successfulRecords++
                            true
                        }
                    }
                }
                .collect { record ->
                    // Stage 4: Write to destination
                    writer.write(record, request)
                }

            writer.flush(request)

        } catch (e: Exception) {
            log.error(e) { "Pipeline execution failed for spec=${request.spec.id}" }
            return ProcessingResult(
                specId = request.spec.id,
                fileName = request.fileName,
                totalRecords = totalRecords,
                successfulRecords = successfulRecords,
                failedRecords = failedRecords,
                correctedRecords = correctedRecords,
                warnings = warningRecords,
                startedAt = startedAt,
                completedAt = Instant.now(),
                status = ProcessingStatus.FAILED,
                errors = processingErrors
            )
        }

        val status = when {
            failedRecords > 0 && successfulRecords > 0 -> ProcessingStatus.COMPLETED_WITH_ERRORS
            failedRecords > 0 -> ProcessingStatus.FAILED
            warningRecords > 0 -> ProcessingStatus.COMPLETED_WITH_WARNINGS
            else -> ProcessingStatus.COMPLETED
        }

        val result = ProcessingResult(
            specId = request.spec.id,
            fileName = request.fileName,
            totalRecords = totalRecords,
            successfulRecords = successfulRecords,
            failedRecords = failedRecords,
            correctedRecords = correctedRecords,
            warnings = warningRecords,
            startedAt = startedAt,
            completedAt = Instant.now(),
            status = status,
            errors = processingErrors
        )

        log.info { "Pipeline complete: $result" }
        return result
    }

    private fun resolveWriter(destination: PipelineDestination): RecordWriter {
        return writers.firstOrNull { it.supports(destination.type) }
            ?: throw IllegalArgumentException("No writer found for destination type: ${destination.type}")
    }
}

data class PipelineRequest(
    val spec: FileSpec,
    val inputStream: InputStream,
    val fileName: String,
    val destination: PipelineDestination,
    val skipInvalidRecords: Boolean = false,
    val delayMs: Long = 0,
    val correlationId: String = java.util.UUID.randomUUID().toString()
)

data class PipelineDestination(
    val type: DestinationType,
    val kafkaTopic: String? = null,
    val outputFilePath: String? = null,
    val webhookUrl: String? = null,
    val outputSpec: OutputSpec? = null
)

enum class DestinationType {
    KAFKA_TOPIC,
    OUTPUT_FILE,
    WEBHOOK,
    DATABASE
}
