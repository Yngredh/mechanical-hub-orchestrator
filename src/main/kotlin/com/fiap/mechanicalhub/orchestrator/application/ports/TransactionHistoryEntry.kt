package com.fiap.mechanicalhub.orchestrator.application.ports

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaStepEnum
import com.fiap.mechanicalhub.orchestrator.domain.enums.TransactionOutcomeEnum
import java.time.Instant
import java.util.UUID

/** Linha append-only de `transaction_history` (faz o papel da `inbox_events`). */
data class TransactionHistoryEntry(
    val transactionId: UUID?,
    val orderId: UUID,
    val seq: Long?,
    val messageType: String,
    val messageKey: String,
    val stepBefore: SagaStepEnum?,
    val stepAfter: SagaStepEnum?,
    val outcome: TransactionOutcomeEnum,
    val receivedAt: Instant,
)
