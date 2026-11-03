package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum
import java.util.UUID

/** Abre a saga; ainda nao existe `transactionId`. */
data class DiagnosisCompleted(
    override val orderId: UUID,
    val repairs: List<RepairReference>,
) : SagaEvent {
    override val type = SagaEventTypeEnum.DIAGNOSIS_COMPLETED
}
