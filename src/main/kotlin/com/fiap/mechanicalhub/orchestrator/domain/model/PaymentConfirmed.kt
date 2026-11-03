package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum
import java.util.UUID

data class PaymentConfirmed(
    override val transactionId: UUID,
    override val orderId: UUID,
    val paymentId: String,
) : SagaResponseEvent {
    override val type = SagaEventTypeEnum.PAYMENT_CONFIRMED
}
