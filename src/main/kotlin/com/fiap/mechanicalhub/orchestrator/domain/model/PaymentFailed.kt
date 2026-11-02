package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.PaymentFailureReasonEnum
import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum
import java.util.UUID

data class PaymentFailed(
    override val transactionId: UUID,
    override val orderId: UUID,
    val reason: PaymentFailureReasonEnum,
) : SagaResponseEvent {
    override val type = SagaEventTypeEnum.PAYMENT_FAILED
}
