package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaCommandTypeEnum
import java.util.UUID

data class ClearStockPendingCommand(
    override val transactionId: UUID,
    override val orderId: UUID,
    override val seq: Long,
) : SagaCommand {
    override val type = SagaCommandTypeEnum.CLEAR_STOCK_PENDING
}
