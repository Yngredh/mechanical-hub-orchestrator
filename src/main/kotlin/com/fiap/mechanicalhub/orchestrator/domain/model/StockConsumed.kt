package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum
import java.util.UUID

data class StockConsumed(
    override val transactionId: UUID,
    override val orderId: UUID,
) : SagaResponseEvent {
    override val type = SagaEventTypeEnum.STOCK_CONSUMED
}
