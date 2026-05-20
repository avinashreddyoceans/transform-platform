package com.transformplatform.api.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.api.dto.CreateSpecRequest
import com.transformplatform.api.dto.SpecResponse
import com.transformplatform.api.persistence.entity.FileSpecJpaEntity
import com.transformplatform.api.persistence.repository.JpaFileSpecRepository
import com.transformplatform.core.spec.model.CorrectionRule
import com.transformplatform.core.spec.model.FieldSpec
import com.transformplatform.core.spec.model.FileFormat
import com.transformplatform.core.spec.model.FileSpec
import com.transformplatform.core.spec.model.OutputSpec
import com.transformplatform.core.spec.model.ValidationRule
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

private val log = KotlinLogging.logger {}

@Service
@Transactional(readOnly = true)
class SpecService(
    private val jpaRepo: JpaFileSpecRepository,
    private val objectMapper: ObjectMapper,
) {

    // ── Type references for JSONB deserialization ──────────────────────────────

    private val fieldListType = object : TypeReference<List<FieldSpec>>() {}
    private val validationType = object : TypeReference<List<ValidationRule>>() {}
    private val correctionType = object : TypeReference<List<CorrectionRule>>() {}
    private val metadataType = object : TypeReference<Map<String, String>>() {}

    // ── CRUD ───────────────────────────────────────────────────────────────────

    @Transactional
    fun createSpec(request: CreateSpecRequest): SpecResponse {
        val id = UUID.randomUUID().toString()
        val now = Instant.now()
        val entity = FileSpecJpaEntity().apply {
            this.id = id
            name = request.name
            description = request.description
            version = request.version
            format = request.format.name
            encoding = request.encoding
            hasHeader = request.hasHeader
            delimiter = request.delimiter
            skipLinesCount = request.skipLinesCount
            fields = objectMapper.writeValueAsString(request.fields)
            validationRules = objectMapper.writeValueAsString(request.validationRules)
            correctionRules = objectMapper.writeValueAsString(request.correctionRules)
            outputSpec = request.outputSpec?.let { objectMapper.writeValueAsString(it) }
            metadata = objectMapper.writeValueAsString(request.metadata)
            createdAt = now
            updatedAt = now
            createdBy = "system"
        }
        val saved = jpaRepo.save(entity)
        log.info { "Created spec: id=$id, name=${saved.name}, format=${saved.format}" }
        return saved.toResponse()
    }

    fun getSpec(id: String): SpecResponse = findEntityOrThrow(id).toResponse()

    fun loadSpec(id: String): FileSpec = findEntityOrThrow(id).toDomain()

    fun listSpecs(format: String?, page: Int, size: Int): List<SpecResponse> {
        val all = if (format != null) {
            jpaRepo.findByFormat(format.uppercase())
        } else {
            jpaRepo.findAll()
        }
        return all.drop(page * size).take(size).map { it.toResponse() }
    }

    @Transactional
    fun updateSpec(id: String, request: CreateSpecRequest): SpecResponse {
        val entity = findEntityOrThrow(id).apply {
            name = request.name
            description = request.description
            format = request.format.name
            encoding = request.encoding
            hasHeader = request.hasHeader
            delimiter = request.delimiter
            fields = objectMapper.writeValueAsString(request.fields)
            validationRules = objectMapper.writeValueAsString(request.validationRules)
            correctionRules = objectMapper.writeValueAsString(request.correctionRules)
            updatedAt = Instant.now()
        }
        return jpaRepo.save(entity).toResponse()
    }

    @Transactional
    fun deleteSpec(id: String) {
        if (!jpaRepo.existsById(id)) throw NoSuchElementException("Spec not found: $id")
        jpaRepo.deleteById(id)
    }

    fun validateSpec(id: String): Map<String, Any> {
        val entity = findEntityOrThrow(id)
        val fields = objectMapper.readValue(entity.fields, fieldListType)
        val issues = buildList {
            if (fields.isEmpty()) add("Spec has no fields defined")
        }
        val validationRules = objectMapper.readValue(entity.validationRules, validationType)
        val correctionRules = objectMapper.readValue(entity.correctionRules, correctionType)
        return mapOf(
            "specId" to id,
            "valid" to issues.isEmpty(),
            "issues" to issues,
            "fieldCount" to fields.size,
            "validationRuleCount" to validationRules.size,
            "correctionRuleCount" to correctionRules.size,
        )
    }

    // ── Mapping ────────────────────────────────────────────────────────────────

    private fun findEntityOrThrow(id: String): FileSpecJpaEntity =
        jpaRepo.findById(id).orElseThrow { NoSuchElementException("Spec not found: $id") }

    private fun FileSpecJpaEntity.toResponse() = SpecResponse(
        id = id,
        name = name,
        description = description,
        version = version,
        format = FileFormat.valueOf(format),
        encoding = encoding,
        hasHeader = hasHeader,
        delimiter = delimiter,
        fieldCount = objectMapper.readValue(fields, fieldListType).size,
        validationRuleCount = objectMapper.readValue(validationRules, validationType).size,
        correctionRuleCount = objectMapper.readValue(correctionRules, correctionType).size,
        createdAt = createdAt,
        updatedAt = updatedAt,
        createdBy = createdBy,
    )

    private fun FileSpecJpaEntity.toDomain() = FileSpec(
        id = id,
        name = name,
        description = description,
        version = version,
        format = FileFormat.valueOf(format),
        encoding = encoding,
        hasHeader = hasHeader,
        delimiter = delimiter,
        recordSeparator = recordSeparator,
        skipLinesCount = skipLinesCount,
        fields = objectMapper.readValue(fields, fieldListType),
        validationRules = objectMapper.readValue(validationRules, validationType),
        correctionRules = objectMapper.readValue(correctionRules, correctionType),
        outputSpec = outputSpec?.let { objectMapper.readValue(it, OutputSpec::class.java) },
        metadata = objectMapper.readValue(metadata, metadataType),
        createdAt = createdAt,
        updatedAt = updatedAt,
        createdBy = createdBy,
    )
}
