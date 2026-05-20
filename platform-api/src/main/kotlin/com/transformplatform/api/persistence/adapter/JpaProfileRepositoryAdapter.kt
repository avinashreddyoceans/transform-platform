package com.transformplatform.api.persistence.adapter

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.api.persistence.entity.ProfileJpaEntity
import com.transformplatform.api.persistence.repository.JpaProfileRepository
import com.transformplatform.common.domain.action.Action
import com.transformplatform.common.domain.profile.Profile
import com.transformplatform.common.domain.profile.ProfileStatus
import com.transformplatform.common.domain.window.WindowConfig
import com.transformplatform.scheduler.repository.ProfileRepository
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

private val log = KotlinLogging.logger {}

// ── JpaProfileRepositoryAdapter ───────────────────────────────────────────────
//
// Bridges the domain-level ProfileRepository interface (used by platform-scheduler
// services) to the JPA persistence layer in platform-api.
//
// Mapping strategy:
//   Entity JSONB strings  →  domain objects via ObjectMapper
//   Domain objects        →  JSON strings via ObjectMapper.writeValueAsString()
//
// WindowConfig and WindowTrigger use @JsonTypeInfo / @JsonSubTypes, so they
// serialise/deserialise polymorphically using the Spring-configured ObjectMapper
// (which includes all custom modules registered in the application context).

@Component
@Transactional(readOnly = true)
class JpaProfileRepositoryAdapter(
    private val jpaRepo: JpaProfileRepository,
    private val objectMapper: ObjectMapper,
) : ProfileRepository {

    // ── Type references for generic deserialization ────────────────────────────
    private val actionListType = object : TypeReference<List<Action>>() {}
    private val tagMapType = object : TypeReference<Map<String, String>>() {}

    // ── Read operations ────────────────────────────────────────────────────────

    override fun findById(id: String): Profile? = jpaRepo.findById(id).orElse(null)?.toDomain()

    override fun findAll(clientId: String?, status: ProfileStatus?): List<Profile> {
        val entities = when {
            clientId != null && status != null ->
                jpaRepo.findByClientIdAndStatusNot(clientId, "DELETED")
                    .filter { it.status == status.name }

            clientId != null ->
                jpaRepo.findActiveByClientId(clientId)

            status != null ->
                jpaRepo.findByStatus(status.name)
                    .filter { it.status != "DELETED" }

            else ->
                jpaRepo.findAllActive()
        }
        return entities.map { it.toDomain() }
    }

    // ── Write operations ───────────────────────────────────────────────────────

    @Transactional
    override fun save(profile: Profile): Profile {
        val entity = profile.toEntity()
        return jpaRepo.save(entity).toDomain()
    }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private fun ProfileJpaEntity.toDomain(): Profile = Profile(
        id = id,
        name = name,
        clientId = clientId,
        description = description,
        status = ProfileStatus.valueOf(status),
        version = version,
        windowConfig = objectMapper.readValue(windowConfig, WindowConfig::class.java),
        actions = objectMapper.readValue(actions, actionListType),
        tags = objectMapper.readValue(tags, tagMapType),
        createdBy = createdBy,
        createdAt = createdAt,
        updatedBy = updatedBy,
        updatedAt = updatedAt,
    )

    private fun Profile.toEntity(): ProfileJpaEntity = ProfileJpaEntity().apply {
        id = this@toEntity.id
        name = this@toEntity.name
        clientId = this@toEntity.clientId
        description = this@toEntity.description
        status = this@toEntity.status.name
        version = this@toEntity.version
        windowConfig = objectMapper.writeValueAsString(this@toEntity.windowConfig)
        actions = objectMapper.writeValueAsString(this@toEntity.actions)
        tags = objectMapper.writeValueAsString(this@toEntity.tags)
        createdBy = this@toEntity.createdBy
        createdAt = this@toEntity.createdAt
        updatedBy = this@toEntity.updatedBy
        updatedAt = this@toEntity.updatedAt
    }
}
