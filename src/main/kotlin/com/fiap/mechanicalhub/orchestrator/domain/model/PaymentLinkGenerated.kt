package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum
import java.time.Instant
import java.util.UUID

data class PaymentLinkGenerated(
    override val transactionId: UUID,
    override val orderId: UUID,
    val paymentLink: String,
    val expiresAt: Instant,
) : SagaResponseEvent {
    override val type = SagaEventTypeEnum.PAYMENT_LINK_GENERATED
}
