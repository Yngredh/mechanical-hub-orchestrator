package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaCommandTypeEnum
import java.util.UUID

data class ConsumeStockCommand(
    override val transactionId: UUID,
    override val orderId: UUID,
    override val seq: Long,
    val reservedMaterials: List<ReservedMaterial>,
) : SagaCommand {
    override val type = SagaCommandTypeEnum.CONSUME_STOCK
}
