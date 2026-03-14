package com.transformplatform.api.service

import com.transformplatform.api.dto.CreateSpecRequest
import com.transformplatform.api.dto.SpecResponse
import com.transformplatform.core.spec.model.FileFormat
import com.transformplatform.core.spec.model.FileSpec
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

private val log = KotlinLogging.logger {}

@Service
class SpecService {

    // In-memory store — replace with JPA repository in Phase 0
    private val specStore = mutableMapOf<String, FileSpec>()

    fun createSpec(request: CreateSpecRequest): SpecResponse {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        val spec = FileSpec(
            id = id,
            name = request.name,
            description = request.description,
            version = request.version,
            format = request.format,
            encoding = request.encoding,
            hasHeader = request.hasHeader,
            delimiter = request.delimiter,
            skipLinesCount = request.skipLinesCount,
            fields = request.fields,
            validationRules = request.validationRules,
            correctionRules = request.correctionRules,
            outputSpec = request.outputSpec,
            metadata = request.metadata,
            createdAt = now,
            updatedAt = now,
        )
        specStore[id] = spec
        log.info { "Created spec: id=$id, name=${spec.name}, format=${spec.format}" }
        return spec.toResponse()
    }

    fun getSpec(id: String): SpecResponse = findOrThrow(id).toResponse()

    fun loadSpec(id: String): FileSpec = findOrThrow(id)

    fun listSpecs(format: String?, page: Int, size: Int): List<SpecResponse> = specStore.values
        .filter { format == null || it.format == FileFormat.valueOf(format.uppercase()) }
        .drop(page * size)
        .take(size)
        .map { it.toResponse() }

    fun updateSpec(id: String, request: CreateSpecRequest): SpecResponse {
        val updated = findOrThrow(id).copy(
            name = request.name,
            description = request.description,
            format = request.format,
            encoding = request.encoding,
            hasHeader = request.hasHeader,
            delimiter = request.delimiter,
            fields = request.fields,
            validationRules = request.validationRules,
            correctionRules = request.correctionRules,
            updatedAt = Instant.now(),
        )
        specStore[id] = updated
        return updated.toResponse()
    }

    fun deleteSpec(id: String) {
        specStore.remove(id) ?: throw NoSuchElementException("Spec not found: $id")
    }

    fun validateSpec(id: String): Map<String, Any> {
        val spec = findOrThrow(id)
        val issues = buildList {
            if (spec.fields.isEmpty()) add("Spec has no fields defined")
        }
        return mapOf(
            "specId" to id,
            "valid" to issues.isEmpty(),
            "issues" to issues,
            "fieldCount" to spec.fields.size,
            "validationRuleCount" to spec.validationRules.size,
        )
    }

    private fun findOrThrow(id: String): FileSpec = specStore[id] ?: throw NoSuchElementException("Spec not found: $id")

    private fun FileSpec.toResponse() = SpecResponse(
        id = id,
        name = name,
        description = description,
        version = version,
        format = format,
        encoding = encoding,
        hasHeader = hasHeader,
        delimiter = delimiter,
        fieldCount = fields.size,
        validationRuleCount = validationRules.size,
        correctionRuleCount = correctionRules.size,
        createdAt = createdAt,
        updatedAt = updatedAt,
        createdBy = createdBy,
    )
}
