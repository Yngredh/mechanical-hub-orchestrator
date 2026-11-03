package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaCommandTypeEnum
import java.math.BigDecimal
import java.util.UUID

data class GeneratePaymentLinkCommand(
    override val transactionId: UUID,
    override val orderId: UUID,
    override val seq: Long,
    val budgetId: UUID,
    val orderCost: BigDecimal,
) : SagaCommand {
    override val type = SagaCommandTypeEnum.GENERATE_PAYMENT_LINK
}
