package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaCommandTypeEnum
import java.util.UUID

data class GenerateBudgetCommand(
    override val transactionId: UUID,
    override val orderId: UUID,
    override val seq: Long,
    val repairs: List<RepairReference>,
    val reservedMaterials: List<ReservedMaterial>,
) : SagaCommand {
    override val type = SagaCommandTypeEnum.GENERATE_BUDGET
}
