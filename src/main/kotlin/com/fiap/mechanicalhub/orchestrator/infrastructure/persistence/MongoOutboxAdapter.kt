package com.fiap.mechanicalhub.orchestrator.infrastructure.persistence

import com.fiap.mechanicalhub.orchestrator.application.ports.OutboxPort
import com.fiap.mechanicalhub.orchestrator.domain.model.SagaCommand
import com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts.toMessage
import com.fiap.mechanicalhub.orchestrator.infrastructure.persistence.documents.OutboxEventDocument
import com.fiap.mechanicalhub.orchestrator.infrastructure.persistence.documents.OutboxStatusEnum
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * `outbox_events` (td_finished_os §0.2): um documento `PENDING` por comando,
 * `_id = eventId` novo e `seq` reservado na saga. O `payload` ja e o corpo
 * JSON do contrato publicado pelo relay.
 */
@Repository
class MongoOutboxAdapter(
    private val mongoTemplate: MongoTemplate,
    private val jsonMapper: JsonMapper,
) : OutboxPort {
    override fun append(
        commands: List<SagaCommand>,
        createdAt: Instant,
    ) {
        if (commands.isEmpty()) return
        mongoTemplate.insertAll(commands.map { it.toOutboxDocument(createdAt) })
    }

    private fun SagaCommand.toOutboxDocument(createdAt: Instant): OutboxEventDocument {
        val eventId = UUID.randomUUID()
        return OutboxEventDocument(
            id = eventId.toString(),
            orderId = orderId.toString(),
            seq = seq,
            eventType = type.messageType,
            payload = jsonMapper.writeValueAsString(toMessage(eventId, createdAt)),
            status = OutboxStatusEnum.PENDING,
            attempts = 0,
            lastError = null,
            createdAt = createdAt,
            sentAt = null,
        )
    }
}
