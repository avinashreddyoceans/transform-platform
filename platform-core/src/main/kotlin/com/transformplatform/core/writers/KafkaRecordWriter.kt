package com.transformplatform.core.writers

import com.fasterxml.jackson.databind.ObjectMapper
import com.transformplatform.core.pipeline.DestinationType
import com.transformplatform.core.pipeline.PipelineRequest
import com.transformplatform.core.spec.model.ParsedRecord
import mu.KotlinLogging
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

private val log = KotlinLogging.logger {}

@Component
class KafkaRecordWriter(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper
) : RecordWriter {

    override val writerName = "KAFKA_WRITER"

    override fun supports(destinationType: DestinationType) = destinationType == DestinationType.KAFKA_TOPIC

    override suspend fun write(record: ParsedRecord, request: PipelineRequest) {
        val topic = request.destination.kafkaTopic
            ?: throw IllegalArgumentException("Kafka destination requires a topic name")

        val event = TransformEvent(
            correlationId = request.correlationId,
            specId = request.spec.id,
            sequenceNumber = record.sequenceNumber,
            fileName = request.fileName,
            fields = record.fields,
            corrected = record.corrected,
            metadata = record.metadata
        )

        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate.send(topic, request.correlationId, payload)

        log.debug { "Sent record ${record.sequenceNumber} to Kafka topic: $topic" }
    }

    override suspend fun flush(request: PipelineRequest) {
        kafkaTemplate.flush()
        log.info { "Kafka writer flushed for correlationId=${request.correlationId}" }
    }
}

data class TransformEvent(
    val correlationId: String,
    val specId: String,
    val sequenceNumber: Long,
    val fileName: String,
    val fields: Map<String, Any?>,
    val corrected: Boolean,
    val metadata: Map<String, String>,
    val eventTimestamp: Long = System.currentTimeMillis()
)
