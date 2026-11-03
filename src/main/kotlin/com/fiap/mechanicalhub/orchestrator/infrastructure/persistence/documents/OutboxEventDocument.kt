package com.fiap.mechanicalhub.orchestrator.infrastructure.persistence.documents

import com.fiap.mechanicalhub.orchestrator.infrastructure.persistence.MongoCollections
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/** Comando a publicar; `_id = eventId` e `payload` e o JSON do contrato (corpo da mensagem SQS). */
@Document(MongoCollections.OUTBOX_EVENTS)
data class OutboxEventDocument(
    @Id val id: String,
    val orderId: String,
    val seq: Long,
    val eventType: String,
    val payload: String,
    val status: OutboxStatusEnum,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Instant,
    val sentAt: Instant?,
)
