package com.transformplatform.api.persistence.adapter

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.api.persistence.entity.WindowJpaEntity
import com.transformplatform.api.persistence.repository.JpaWindowRepository
import com.transformplatform.common.domain.trigger.TriggerFireState
import com.transformplatform.common.domain.window.FileHandle
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.domain.window.WindowStatus
import com.transformplatform.scheduler.repository.WindowInstanceRepository
import mu.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = KotlinLogging.logger {}

// ── JpaWindowRepositoryAdapter ────────────────────────────────────────────────
//
// Bridges the domain-level WindowInstanceRepository interface (used by
// platform-scheduler services) to the JPA persistence layer in platform-api.
//
// JSONB columns (arrivedFileHandles, closeTriggerState) are serialised and
// deserialised as JSON strings using the application-configured ObjectMapper.

@Component
@Transactional(readOnly = true)
class JpaWindowRepositoryAdapter(
    private val jpaRepo: JpaWindowRepository,
    private val objectMapper: ObjectMapper,
) : WindowInstanceRepository {

    private val fileHandleListType = object : TypeReference<List<FileHandle>>() {}

    // ── Read operations ────────────────────────────────────────────────────────

    override fun findById(id: String): WindowInstance? = jpaRepo.findById(id).orElse(null)?.toDomain()

    override fun findByProfileId(profileId: String): List<WindowInstance> =
        jpaRepo.findByProfileIdOrderByCreatedAtDesc(profileId).map { it.toDomain() }

    override fun findPendingForProfile(profileId: String): WindowInstance? =
        jpaRepo.findFirstByProfileIdAndStatus(profileId, "PENDING")?.toDomain()

    override fun findActiveForProfile(profileId: String): WindowInstance? {
        val open = jpaRepo.findFirstByProfileIdAndStatus(profileId, "OPEN")
        if (open != null) return open.toDomain()
        return jpaRepo.findFirstByProfileIdAndStatus(profileId, "CLOSING")?.toDomain()
    }

    // ── Orchestrator hot-path queries ──────────────────────────────────────────

    override fun findDueToOpen(now: Instant): List<WindowInstance> = jpaRepo.findDueToOpen(now).map { it.toDomain() }

    override fun findDueToClose(now: Instant): List<WindowInstance> = jpaRepo.findDueToClose(now).map { it.toDomain() }

    override fun findOpenWithSessionGapTrigger(): List<WindowInstance> =
        // Return all OPEN windows — caller filters by trigger type from profile config.
        // Phase 1b: denormalize trigger_type onto window row for SQL-level filtering.
        jpaRepo.findByStatus("OPEN").map { it.toDomain() }

    // ── Write operations ───────────────────────────────────────────────────────

    @Transactional
    override fun save(instance: WindowInstance): WindowInstance = jpaRepo.save(instance.toEntity()).toDomain()

    @Transactional
    override fun updateStatus(id: String, newStatus: WindowStatus, reason: String?, timestamp: Instant): WindowInstance {
        val entity = jpaRepo.findById(id).orElseThrow { NoSuchElementException("Window $id not found") }
        entity.status = newStatus.name
        entity.statusReason = reason
        entity.updatedAt = timestamp
        when (newStatus) {
            WindowStatus.OPEN -> entity.openedAt = timestamp
            WindowStatus.CLOSING -> entity.closingStartedAt = timestamp
            WindowStatus.CLOSED,
            WindowStatus.FORCE_CLOSED,
            WindowStatus.ERROR,
            -> entity.closedAt = timestamp
            else -> Unit
        }
        return jpaRepo.save(entity).toDomain()
    }

    @Transactional
    override fun setScheduledCloseAt(id: String, scheduledCloseAt: Instant): WindowInstance {
        jpaRepo.setScheduledCloseAt(id, scheduledCloseAt, Instant.now())
        return findById(id) ?: throw NoSuchElementException("Window $id not found")
    }

    @Transactional
    override fun incrementEventCount(id: String, delta: Int): Int {
        jpaRepo.incrementEventCount(id, delta, Instant.now())
        return (findById(id) ?: throw NoSuchElementException("Window $id not found")).eventCount
    }

    @Transactional
    override fun addArrivedFile(id: String, file: FileHandle): WindowInstance {
        val entity = jpaRepo.findById(id).orElseThrow { NoSuchElementException("Window $id not found") }
        val current = objectMapper.readValue(entity.arrivedFileHandles, fileHandleListType)
        entity.arrivedFileHandles = objectMapper.writeValueAsString(current + file)
        entity.updatedAt = Instant.now()
        return jpaRepo.save(entity).toDomain()
    }

    @Transactional
    override fun updateCloseTriggerState(id: String, fireState: TriggerFireState): WindowInstance {
        val entity = jpaRepo.findById(id).orElseThrow { NoSuchElementException("Window $id not found") }
        entity.closeTriggerState = objectMapper.writeValueAsString(fireState)
        entity.updatedAt = Instant.now()
        return jpaRepo.save(entity).toDomain()
    }

    @Transactional
    override fun deleteById(id: String) {
        jpaRepo.deleteById(id)
    }

    override fun findByStatus(status: WindowStatus): List<WindowInstance> = jpaRepo.findByStatus(status.name).map { it.toDomain() }

    override fun findAll(): List<WindowInstance> = jpaRepo.findAll().map { it.toDomain() }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private fun WindowJpaEntity.toDomain(): WindowInstance = WindowInstance(
        id = id,
        profileId = profileId,
        profileVersion = profileVersion,
        status = WindowStatus.valueOf(status),
        scheduledOpenAt = scheduledOpenAt,
        scheduledCloseAt = scheduledCloseAt,
        openedAt = openedAt,
        closingStartedAt = closingStartedAt,
        closedAt = closedAt,
        statusReason = statusReason,
        eventCount = eventCount,
        arrivedFileHandles = objectMapper.readValue(arrivedFileHandles, object : TypeReference<List<FileHandle>>() {}),
        closeTriggerState = objectMapper.readValue(closeTriggerState, TriggerFireState::class.java),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun WindowInstance.toEntity(): WindowJpaEntity = WindowJpaEntity().apply {
        id = this@toEntity.id
        profileId = this@toEntity.profileId
        profileVersion = this@toEntity.profileVersion
        status = this@toEntity.status.name
        scheduledOpenAt = this@toEntity.scheduledOpenAt
        scheduledCloseAt = this@toEntity.scheduledCloseAt
        openedAt = this@toEntity.openedAt
        closingStartedAt = this@toEntity.closingStartedAt
        closedAt = this@toEntity.closedAt
        statusReason = this@toEntity.statusReason
        eventCount = this@toEntity.eventCount
        arrivedFileHandles = objectMapper.writeValueAsString(this@toEntity.arrivedFileHandles)
        closeTriggerState = objectMapper.writeValueAsString(this@toEntity.closeTriggerState)
        createdAt = this@toEntity.createdAt
        updatedAt = this@toEntity.updatedAt
    }
}
