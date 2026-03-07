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

@Component
class TransformationPipeline(
    private val parserRegistry: ParserRegistry,
    private val correctionEngine: CorrectionEngine,
    private val validationEngine: ValidationEngine,
    private val writers: List<RecordWriter>
) {

    suspend fun execute(request: PipelineRequest): ProcessingResult {
        val startedAt = Instant.now()
        log.info { "Pipeline starting: spec=${request.spec.id}, file=${request.fileName}" }

        var totalRecords = 0L
        var successfulRecords = 0L
        var failedRecords = 0L
        var correctedRecords = 0L
        var warningRecords = 0L
        val processingErrors = mutableListOf<ProcessingError>()

        val writer = resolveWriter(request.destination)

        return runCatching {
            parserRegistry.parse(request.inputStream, request.spec)
                .map { correctionEngine.applyCorrections(it, request.spec) }
                .map { validationEngine.validate(it, request.spec) }
                .filter { record ->
                    totalRecords++
                    when {
                        record.hasFatalErrors -> {
                            failedRecords++
                            record.errors.forEach {
                                processingErrors.add(ProcessingError(record.sequenceNumber, it.field, it.message, it.severity))
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
                .collect { writer.write(it, request) }

            writer.flush(request)
            determineStatus(failedRecords, successfulRecords, warningRecords)
        }.fold(
            onSuccess = { status ->
                buildResult(request, startedAt, totalRecords, successfulRecords, failedRecords, correctedRecords, warningRecords, processingErrors, status)
            },
            onFailure = { e ->
                log.error(e) { "Pipeline execution failed for spec=${request.spec.id}" }
                buildResult(request, startedAt, totalRecords, successfulRecords, failedRecords, correctedRecords, warningRecords, processingErrors, ProcessingStatus.FAILED)
            }
        ).also { log.info { "Pipeline complete: $it" } }
    }

    private fun resolveWriter(destination: PipelineDestination): RecordWriter =
        writers.firstOrNull { it.supports(destination.type) }
            ?: throw IllegalArgumentException("No writer found for destination type: ${destination.type}")

    private fun determineStatus(failed: Long, successful: Long, warnings: Long): ProcessingStatus = when {
        failed > 0 && successful > 0 -> ProcessingStatus.COMPLETED_WITH_ERRORS
        failed > 0 -> ProcessingStatus.FAILED
        warnings > 0 -> ProcessingStatus.COMPLETED_WITH_WARNINGS
        else -> ProcessingStatus.COMPLETED
    }

    private fun buildResult(
        request: PipelineRequest,
        startedAt: Instant,
        totalRecords: Long,
        successfulRecords: Long,
        failedRecords: Long,
        correctedRecords: Long,
        warningRecords: Long,
        errors: List<ProcessingError>,
        status: ProcessingStatus
    ) = ProcessingResult(
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
        errors = errors
    )
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
