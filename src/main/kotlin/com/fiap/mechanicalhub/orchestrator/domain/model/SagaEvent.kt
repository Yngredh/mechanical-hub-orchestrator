package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum
import java.util.UUID

/** Evento consumido pela saga, ja convertido do contrato JSON. */
sealed interface SagaEvent {
    val type: SagaEventTypeEnum
    val orderId: UUID
}
