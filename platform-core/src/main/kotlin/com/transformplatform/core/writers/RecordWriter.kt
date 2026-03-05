package com.transformplatform.core.writers

import com.transformplatform.core.pipeline.DestinationType
import com.transformplatform.core.pipeline.PipelineRequest
import com.transformplatform.core.spec.model.ParsedRecord

/**
 * Contract for all output writers.
 * Implement this to add a new output destination — Kafka, file, webhook, DB, SFTP, etc.
 */
interface RecordWriter {
    fun supports(destinationType: DestinationType): Boolean
    suspend fun write(record: ParsedRecord, request: PipelineRequest)
    suspend fun flush(request: PipelineRequest) {} // called after all records written
    val writerName: String
}
