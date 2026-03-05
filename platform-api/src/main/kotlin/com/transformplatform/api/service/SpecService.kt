package com.transformplatform.api.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.api.dto.CreateSpecRequest
import com.transformplatform.api.dto.SpecResponse
import com.transformplatform.core.spec.model.FileSpec
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*

private val log = KotlinLogging.logger {}

@Service
class SpecService(private val objectMapper: ObjectMapper) {

    // In-memory store for now — replace with PostgreSQL JPA repository
    private val specStore = mutableMapOf<String, FileSpec>()

    fun createSpec(request: CreateSpecRequest): SpecResponse {
        val id = UUID.randomUUID().toString()
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
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        specStore[id] = spec
        log.info { "Created spec: id=$id, name=${spec.name}, format=${spec.format}" }
        return spec.toResponse()
    }

    fun getSpec(id: String): SpecResponse =
        (specStore[id] ?: throw NoSuchElementException("Spec not found: $id")).toResponse()

    fun loadSpec(id: String): FileSpec =
        specStore[id] ?: throw NoSuchElementException("Spec not found: $id")

    fun listSpecs(format: String?, page: Int, size: Int): List<SpecResponse> {
        return specStore.values
            .filter { format == null || it.format.name == format.uppercase() }
            .drop(page * size)
            .take(size)
            .map { it.toResponse() }
    }

    fun updateSpec(id: String, request: CreateSpecRequest): SpecResponse {
        val existing = specStore[id] ?: throw NoSuchElementException("Spec not found: $id")
        val updated = existing.copy(
            name = request.name,
            description = request.description,
            format = request.format,
            encoding = request.encoding,
            hasHeader = request.hasHeader,
            delimiter = request.delimiter,
            fields = request.fields,
            validationRules = request.validationRules,
            correctionRules = request.correctionRules,
            updatedAt = Instant.now()
        )
        specStore[id] = updated
        return updated.toResponse()
    }

    fun deleteSpec(id: String) {
        specStore.remove(id) ?: throw NoSuchElementException("Spec not found: $id")
    }

    fun validateSpec(id: String): Map<String, Any> {
        val spec = specStore[id] ?: throw NoSuchElementException("Spec not found: $id")
        val issues = mutableListOf<String>()
        if (spec.fields.isEmpty()) issues.add("Spec has no fields defined")
        spec.fields.filter { it.required && it.defaultValue == null && !it.nullable }
            .forEach { /* required fields without defaults — warn if no validation rule exists */ }
        return mapOf(
            "specId" to id,
            "valid" to issues.isEmpty(),
            "issues" to issues,
            "fieldCount" to spec.fields.size,
            "validationRuleCount" to spec.validationRules.size
        )
    }

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
        createdBy = createdBy
    )
}
