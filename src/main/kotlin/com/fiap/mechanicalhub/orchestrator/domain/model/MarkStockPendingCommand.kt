package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaCommandTypeEnum
import java.util.UUID

data class MarkStockPendingCommand(
    override val transactionId: UUID,
    override val orderId: UUID,
    override val seq: Long,
) : SagaCommand {
    override val type = SagaCommandTypeEnum.MARK_STOCK_PENDING
}
