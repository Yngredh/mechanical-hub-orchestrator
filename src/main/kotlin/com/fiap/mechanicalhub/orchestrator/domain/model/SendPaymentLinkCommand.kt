package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaCommandTypeEnum
import java.util.UUID

data class SendPaymentLinkCommand(
    override val transactionId: UUID,
    override val orderId: UUID,
    override val seq: Long,
    val paymentLink: String,
) : SagaCommand {
    override val type = SagaCommandTypeEnum.SEND_PAYMENT_LINK
}
