package com.transformplatform.api.persistence.adapter

import com.transformplatform.api.persistence.entity.FileLogJpaEntity
import com.transformplatform.api.persistence.repository.JpaFileLogRepository
import com.transformplatform.common.domain.filelog.FileDirection
import com.transformplatform.common.domain.filelog.FileLog
import com.transformplatform.common.domain.filelog.FileLogStatus
import com.transformplatform.scheduler.repository.FileLogRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@Transactional(readOnly = true)
class JpaFileLogRepositoryAdapter(
    private val jpaRepo: JpaFileLogRepository,
) : FileLogRepository {

    // ── Write ──────────────────────────────────────────────────────────────────

    @Transactional
    override fun save(fileLog: FileLog): FileLog = jpaRepo.save(fileLog.toEntity()).toDomain()

    // ── Read ───────────────────────────────────────────────────────────────────

    override fun findById(id: String): FileLog? = jpaRepo.findById(id).orElse(null)?.toDomain()

    override fun findByWindowId(windowId: String): List<FileLog> =
        jpaRepo.findByWindowIdOrderByArrivedAtDesc(windowId).map { it.toDomain() }

    override fun findByProfileId(profileId: String, limit: Int): List<FileLog> =
        jpaRepo.findByProfileIdOrderByArrivedAtDesc(profileId, PageRequest.of(0, limit))
            .map { it.toDomain() }

    override fun findByDirection(direction: FileDirection, limit: Int): List<FileLog> =
        jpaRepo.findByDirectionOrderByArrivedAtDesc(direction.name, PageRequest.of(0, limit))
            .map { it.toDomain() }

    override fun findByGeneratedByExecutionId(executionId: String): List<FileLog> =
        jpaRepo.findByGeneratedByExecutionId(executionId).map { it.toDomain() }

    override fun findAll(limit: Int): List<FileLog> = jpaRepo.findAllPaged(PageRequest.of(0, limit)).map { it.toDomain() }

    override fun existsInbound(windowId: String, integrationId: String, remoteIdentifier: String): Boolean =
        jpaRepo.existsInbound(windowId, integrationId, remoteIdentifier)

    // ── Mapping ────────────────────────────────────────────────────────────────

    private fun FileLog.toEntity() = FileLogJpaEntity().apply {
        id = this@toEntity.id
        direction = this@toEntity.direction.name
        integrationId = this@toEntity.integrationId
        remoteIdentifier = this@toEntity.remoteIdentifier
        fileName = this@toEntity.fileName
        windowId = this@toEntity.windowId
        profileId = this@toEntity.profileId
        clientId = this@toEntity.clientId
        generatedByExecutionId = this@toEntity.generatedByExecutionId
        fileSizeBytes = this@toEntity.fileSizeBytes
        contentChecksum = this@toEntity.contentChecksum
        mimeType = this@toEntity.mimeType
        status = this@toEntity.status.name
        errorMessage = this@toEntity.errorMessage
        arrivedAt = this@toEntity.arrivedAt
        processedAt = this@toEntity.processedAt
    }

    private fun FileLogJpaEntity.toDomain() = FileLog(
        id = id,
        direction = FileDirection.valueOf(direction),
        integrationId = integrationId,
        remoteIdentifier = remoteIdentifier,
        fileName = fileName,
        windowId = windowId,
        profileId = profileId,
        clientId = clientId,
        generatedByExecutionId = generatedByExecutionId,
        fileSizeBytes = fileSizeBytes,
        contentChecksum = contentChecksum,
        mimeType = mimeType,
        status = FileLogStatus.valueOf(status),
        errorMessage = errorMessage,
        arrivedAt = arrivedAt,
        processedAt = processedAt,
    )
}
