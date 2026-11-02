package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaCommandTypeEnum
import java.util.UUID

/**
 * Comando emitido pela saga, gravado no outbox com o `seq` reservado na saga
 * (ordem de publicacao por `orderId`).
 */
sealed interface SagaCommand {
    val type: SagaCommandTypeEnum
    val transactionId: UUID
    val orderId: UUID
    val seq: Long

    val idempotencyKey: String get() = "$transactionId:${type.step}"
}
