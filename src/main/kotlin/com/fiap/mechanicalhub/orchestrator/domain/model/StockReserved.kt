package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum
import java.util.UUID

data class StockReserved(
    override val transactionId: UUID,
    override val orderId: UUID,
    val reservedMaterials: List<ReservedMaterial>,
) : SagaResponseEvent {
    override val type = SagaEventTypeEnum.STOCK_RESERVED
}
