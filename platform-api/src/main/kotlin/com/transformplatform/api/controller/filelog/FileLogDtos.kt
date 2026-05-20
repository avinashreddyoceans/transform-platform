package com.transformplatform.api.controller.filelog

import com.transformplatform.common.domain.filelog.FileDirection
import com.transformplatform.common.domain.filelog.FileLog
import com.transformplatform.common.domain.filelog.FileLogStatus
import java.time.Instant

// ── FileLog DTOs ──────────────────────────────────────────────────────────────

data class FileLogResponse(
    val id: String,
    val direction: FileDirection,
    val integrationId: String?,
    val remoteIdentifier: String?,
    val fileName: String,
    val windowId: String?,
    val profileId: String?,
    val clientId: String?,
    val generatedByExecutionId: String?,
    val fileSizeBytes: Long?,
    val contentChecksum: String?,
    val mimeType: String?,
    val status: FileLogStatus,
    val errorMessage: String?,
    val arrivedAt: Instant,
    val processedAt: Instant?,
)

// ── Ingest request (POST /api/files/inbound) ─────────────────────────────────

data class IngestFileRequest(
    val integrationId: String?,
    val remoteIdentifier: String?,
    val fileName: String,
    val windowId: String?,
    val profileId: String?,
    val clientId: String?,
    val fileSizeBytes: Long?,
    val contentChecksum: String?,
    val mimeType: String?,
)

// ── Mapper ────────────────────────────────────────────────────────────────────

fun FileLog.toResponse() = FileLogResponse(
    id = id,
    direction = direction,
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
    status = status,
    errorMessage = errorMessage,
    arrivedAt = arrivedAt,
    processedAt = processedAt,
)
