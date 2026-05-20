package com.transformplatform.api.persistence.adapter

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.api.persistence.entity.WindowDataJpaEntity
import com.transformplatform.api.persistence.repository.JpaWindowDataRepository
import com.transformplatform.common.domain.window.WindowDataRecord
import com.transformplatform.common.domain.window.WindowDataRecordType
import com.transformplatform.scheduler.repository.DuplicateRecordException
import com.transformplatform.scheduler.repository.WindowDataRepository
import mu.KotlinLogging
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private val log = KotlinLogging.logger {}

@Component
@Transactional(readOnly = true)
class JpaWindowDataRepositoryAdapter(
    private val jpaRepo: JpaWindowDataRepository,
    private val objectMapper: ObjectMapper,
) : WindowDataRepository {

    private val payloadType = object : TypeReference<Map<String, Any>>() {}

    // ── Write ──────────────────────────────────────────────────────────────────

    @Transactional
    override fun save(record: WindowDataRecord): WindowDataRecord {
        try {
            val entity = record.toEntity()
            return jpaRepo.save(entity).toDomain()
        } catch (ex: DataIntegrityViolationException) {
            // Capture to local val for cross-module smart cast
            val dedupKey = record.deduplicationKey
            if (dedupKey != null) {
                throw DuplicateRecordException(record.windowId, dedupKey)
            }
            throw ex
        }
    }

    @Transactional
    override fun saveAll(records: List<WindowDataRecord>, skipDuplicates: Boolean): List<WindowDataRecord> {
        val saved = mutableListOf<WindowDataRecord>()
        for (record in records) {
            try {
                saved.add(save(record))
            } catch (ex: DuplicateRecordException) {
                if (!skipDuplicates) throw ex
                log.debug { "Skipping duplicate: windowId=${record.windowId}, key=${record.deduplicationKey}" }
            }
        }
        return saved
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    override fun findById(id: String): WindowDataRecord? = jpaRepo.findById(id).orElse(null)?.toDomain()

    override fun findByWindow(windowId: String, recordType: WindowDataRecordType?): List<WindowDataRecord> = if (recordType == null) {
        jpaRepo.findByWindowIdOrderByArrivedAtAsc(windowId)
    } else {
        jpaRepo.findByWindowIdAndRecordTypeOrderByArrivedAtAsc(windowId, recordType.name)
    }.map { it.toDomain() }

    override fun countByWindow(windowId: String, recordType: WindowDataRecordType?): Int = if (recordType == null) {
        jpaRepo.countByWindowId(windowId)
    } else {
        jpaRepo.countByWindowIdAndRecordType(windowId, recordType.name)
    }

    override fun isDuplicate(windowId: String, deduplicationKey: String): Boolean =
        jpaRepo.existsByWindowIdAndDeduplicationKey(windowId, deduplicationKey)

    override fun findByProfile(profileId: String, recordType: WindowDataRecordType?, since: Instant?, limit: Int): List<WindowDataRecord> =
        jpaRepo.findByProfilePaged(
            profileId = profileId,
            recordType = recordType?.name,
            since = since,
            pageable = PageRequest.of(0, limit),
        ).map { it.toDomain() }

    @Transactional
    override fun deleteByWindow(windowId: String): Int = jpaRepo.deleteAllByWindowId(windowId)

    // ── Mapping ────────────────────────────────────────────────────────────────

    private fun WindowDataRecord.toEntity() = WindowDataJpaEntity().apply {
        id = this@toEntity.id
        windowId = this@toEntity.windowId
        profileId = this@toEntity.profileId
        clientId = this@toEntity.clientId
        recordType = this@toEntity.recordType.name
        sourceIntegrationId = this@toEntity.sourceIntegrationId
        deduplicationKey = this@toEntity.deduplicationKey
        eventTimestamp = this@toEntity.eventTimestamp
        payload = objectMapper.writeValueAsString(this@toEntity.payload)
        arrivedAt = this@toEntity.arrivedAt
    }

    private fun WindowDataJpaEntity.toDomain() = WindowDataRecord(
        id = id,
        windowId = windowId,
        profileId = profileId,
        clientId = clientId,
        recordType = WindowDataRecordType.valueOf(recordType),
        sourceIntegrationId = sourceIntegrationId,
        deduplicationKey = deduplicationKey,
        eventTimestamp = eventTimestamp,
        payload = objectMapper.readValue(payload, payloadType),
        arrivedAt = arrivedAt,
    )
}
