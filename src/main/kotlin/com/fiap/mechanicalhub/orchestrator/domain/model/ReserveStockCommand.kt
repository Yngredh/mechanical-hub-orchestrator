package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaCommandTypeEnum
import java.util.UUID

data class ReserveStockCommand(
    override val transactionId: UUID,
    override val orderId: UUID,
    override val seq: Long,
    val repairs: List<RepairReference>,
) : SagaCommand {
    override val type = SagaCommandTypeEnum.RESERVE_STOCK
}
