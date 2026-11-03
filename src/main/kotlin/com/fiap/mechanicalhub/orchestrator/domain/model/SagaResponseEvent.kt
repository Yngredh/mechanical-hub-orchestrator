package com.fiap.mechanicalhub.orchestrator.domain.model

import java.util.UUID

/** Resposta de um passo de uma saga existente, sempre aplicada com a guarda de passo. */
sealed interface SagaResponseEvent : SagaEvent {
    val transactionId: UUID
}
