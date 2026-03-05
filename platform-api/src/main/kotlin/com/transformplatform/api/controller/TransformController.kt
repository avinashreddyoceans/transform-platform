package com.transformplatform.api.controller

import com.transformplatform.api.dto.TransformRequest
import com.transformplatform.api.dto.TransformResponse
import com.transformplatform.api.service.TransformService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import mu.KotlinLogging
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

private val log = KotlinLogging.logger {}

@RestController
@RequestMapping("/api/v1/transform")
@Tag(name = "Transform", description = "File transformation and processing")
class TransformController(private val transformService: TransformService) {

    /**
     * Upload a file, transform using the given specId, emit to Kafka or produce output file.
     */
    @PostMapping("/file-to-events", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Operation(
        summary = "Transform file → events",
        description = "Parse a file using the given spec and publish each record as a Kafka event"
    )
    suspend fun fileToEvents(
        @RequestPart("file") file: MultipartFile,
        @RequestPart("specId") specId: String,
        @RequestPart("kafkaTopic") kafkaTopic: String,
        @RequestPart(value = "skipInvalidRecords", required = false) skipInvalid: String?
    ): ResponseEntity<TransformResponse> {
        log.info { "file-to-events: file=${file.originalFilename}, spec=$specId, topic=$kafkaTopic" }
        val result = transformService.fileToEvents(
            file = file,
            specId = specId,
            kafkaTopic = kafkaTopic,
            skipInvalidRecords = skipInvalid?.toBooleanStrictOrNull() ?: false
        )
        return ResponseEntity.ok(result)
    }

    /**
     * Schedule a transformation — run immediately or with a delay.
     */
    @PostMapping("/schedule")
    @Operation(
        summary = "Schedule a transformation",
        description = "Schedule a file transformation to run immediately, after a delay, or on a cron schedule"
    )
    fun scheduleTransform(@RequestBody request: TransformRequest): ResponseEntity<TransformResponse> {
        log.info { "Scheduling transform: $request" }
        val result = transformService.scheduleTransform(request)
        return ResponseEntity.ok(result)
    }

    /**
     * Get the status of an in-progress or completed transformation.
     */
    @GetMapping("/status/{correlationId}")
    @Operation(summary = "Get transformation status")
    fun getStatus(@PathVariable correlationId: String): ResponseEntity<TransformResponse> {
        return ResponseEntity.ok(transformService.getStatus(correlationId))
    }
}
