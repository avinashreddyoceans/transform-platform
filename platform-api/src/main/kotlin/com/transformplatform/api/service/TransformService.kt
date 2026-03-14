package com.transformplatform.api.service

import com.transformplatform.api.dto.ErrorDetail
import com.transformplatform.api.dto.TransformRequest
import com.transformplatform.api.dto.TransformResponse
import com.transformplatform.core.pipeline.DestinationType
import com.transformplatform.core.pipeline.PipelineDestination
import com.transformplatform.core.pipeline.PipelineRequest
import com.transformplatform.core.pipeline.TransformationPipeline
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

private val log = KotlinLogging.logger {}

@Service
class TransformService(
    private val specService: SpecService,
    private val pipeline: TransformationPipeline,
) {

    // In-memory status store — replace with Redis/DB in production
    private val statusStore = mutableMapOf<String, TransformResponse>()

    suspend fun fileToEvents(file: MultipartFile, specId: String, kafkaTopic: String, skipInvalidRecords: Boolean): TransformResponse {
        val spec = specService.loadSpec(specId)
        val correlationId = UUID.randomUUID().toString()

        val result = pipeline.execute(
            PipelineRequest(
                spec = spec,
                inputStream = file.inputStream,
                fileName = file.originalFilename ?: "unknown",
                destination = PipelineDestination(
                    type = DestinationType.KAFKA_TOPIC,
                    kafkaTopic = kafkaTopic,
                ),
                skipInvalidRecords = skipInvalidRecords,
                correlationId = correlationId,
            ),
        )

        return TransformResponse(
            correlationId = correlationId,
            status = result.status.name,
            specId = specId,
            fileName = file.originalFilename,
            totalRecords = result.totalRecords,
            successfulRecords = result.successfulRecords,
            failedRecords = result.failedRecords,
            correctedRecords = result.correctedRecords,
            durationMs = result.durationMs,
            errors = result.errors.map {
                ErrorDetail(it.sequenceNumber, it.field, it.message, it.severity)
            },
        ).also { statusStore[correlationId] = it }
    }

    fun scheduleTransform(request: TransformRequest): TransformResponse {
        // TODO: wire into Quartz scheduler for delayed/cron execution
        val correlationId = UUID.randomUUID().toString()
        log.info { "Scheduled transform queued: correlationId=$correlationId, delay=${request.delayMs}ms" }
        return TransformResponse(
            correlationId = correlationId,
            status = "SCHEDULED",
            specId = request.specId,
            message = "Transform scheduled. Use correlationId to check status.",
        ).also { statusStore[correlationId] = it }
    }

    fun getStatus(correlationId: String): TransformResponse = statusStore[correlationId]
        ?: TransformResponse(correlationId = correlationId, status = "NOT_FOUND", specId = "")
}
