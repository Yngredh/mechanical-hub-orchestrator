package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum
import java.util.UUID

data class BudgetGenerated(
    override val transactionId: UUID,
    override val orderId: UUID,
    val budget: Budget,
) : SagaResponseEvent {
    override val type = SagaEventTypeEnum.BUDGET_GENERATED
}
