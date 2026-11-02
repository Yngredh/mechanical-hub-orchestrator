package com.fiap.mechanicalhub.orchestrator.infrastructure.persistence.documents

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaStepEnum
import com.fiap.mechanicalhub.orchestrator.domain.enums.TransactionOutcomeEnum
import com.fiap.mechanicalhub.orchestrator.infrastructure.persistence.MongoCollections
import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/** Linha append-only da trilha da saga; `messageKey` tem indice unico (deduplicacao). */
@Document(MongoCollections.TRANSACTION_HISTORY)
data class TransactionHistoryDocument(
    @Id val id: ObjectId? = null,
    val transactionId: String?,
    val orderId: String,
    val seq: Long?,
    val messageType: String,
    val messageKey: String,
    val stepBefore: SagaStepEnum?,
    val stepAfter: SagaStepEnum?,
    val outcome: TransactionOutcomeEnum,
    val receivedAt: Instant,
)
