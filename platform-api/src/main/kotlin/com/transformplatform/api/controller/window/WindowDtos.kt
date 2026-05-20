package com.transformplatform.api.controller.window

import com.transformplatform.common.domain.window.FileHandle
import com.transformplatform.common.domain.window.WindowDataRecord
import com.transformplatform.common.domain.window.WindowDataRecordType
import com.transformplatform.common.domain.window.WindowInstance
import com.transformplatform.common.domain.window.WindowStatus
import java.time.Instant

// ── Window DTOs ───────────────────────────────────────────────────────────────

data class WindowSummaryResponse(
    val id: String,
    val profileId: String,
    val profileVersion: Int,
    val status: WindowStatus,
    val scheduledOpenAt: Instant?,
    val scheduledCloseAt: Instant?,
    val openedAt: Instant?,
    val closingStartedAt: Instant?,
    val closedAt: Instant?,
    val eventCount: Int,
    val fileCount: Int,
    val statusReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class WindowDetailResponse(
    val id: String,
    val profileId: String,
    val profileVersion: Int,
    val status: WindowStatus,
    val scheduledOpenAt: Instant?,
    val scheduledCloseAt: Instant?,
    val openedAt: Instant?,
    val closingStartedAt: Instant?,
    val closedAt: Instant?,
    val statusReason: String?,
    val eventCount: Int,
    val arrivedFileHandles: List<FileHandleResponse>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class FileHandleResponse(
    val integrationId: String,
    val fileName: String,
    val remotePath: String,
    val fileSizeBytes: Long?,
    val arrivedAt: Instant,
    val contentChecksum: String?,
)

data class WindowDataResponse(
    val id: String,
    val windowId: String,
    val profileId: String,
    val clientId: String,
    val recordType: WindowDataRecordType,
    val sourceIntegrationId: String?,
    val deduplicationKey: String?,
    val eventTimestamp: Instant?,
    val payload: Map<String, Any>,
    val arrivedAt: Instant,
)

data class WindowEventsResponse(
    val windowId: String,
    val total: Int,
    val recordType: String?,
    val events: List<WindowDataResponse>,
)

// ── Mappers ───────────────────────────────────────────────────────────────────

fun WindowInstance.toSummary() = WindowSummaryResponse(
    id = id,
    profileId = profileId,
    profileVersion = profileVersion,
    status = status,
    scheduledOpenAt = scheduledOpenAt,
    scheduledCloseAt = scheduledCloseAt,
    openedAt = openedAt,
    closingStartedAt = closingStartedAt,
    closedAt = closedAt,
    eventCount = eventCount,
    fileCount = arrivedFileHandles.size,
    statusReason = statusReason,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun WindowInstance.toDetail() = WindowDetailResponse(
    id = id,
    profileId = profileId,
    profileVersion = profileVersion,
    status = status,
    scheduledOpenAt = scheduledOpenAt,
    scheduledCloseAt = scheduledCloseAt,
    openedAt = openedAt,
    closingStartedAt = closingStartedAt,
    closedAt = closedAt,
    statusReason = statusReason,
    eventCount = eventCount,
    arrivedFileHandles = arrivedFileHandles.map { it.toResponse() },
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun FileHandle.toResponse() = FileHandleResponse(
    integrationId = integrationId,
    fileName = fileName,
    remotePath = remotePath,
    fileSizeBytes = fileSizeBytes,
    arrivedAt = arrivedAt,
    contentChecksum = contentChecksum,
)

fun WindowDataRecord.toResponse() = WindowDataResponse(
    id = id,
    windowId = windowId,
    profileId = profileId,
    clientId = clientId,
    recordType = recordType,
    sourceIntegrationId = sourceIntegrationId,
    deduplicationKey = deduplicationKey,
    eventTimestamp = eventTimestamp,
    payload = payload,
    arrivedAt = arrivedAt,
)
