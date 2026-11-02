package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.CancelOrderReasonEnum
import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaCommandTypeEnum
import java.util.UUID

data class CancelOrderCommand(
    override val transactionId: UUID,
    override val orderId: UUID,
    override val seq: Long,
    val reason: CancelOrderReasonEnum = CancelOrderReasonEnum.PAYMENT_NOT_CONFIRMED,
) : SagaCommand {
    override val type = SagaCommandTypeEnum.CANCEL_ORDER
}
