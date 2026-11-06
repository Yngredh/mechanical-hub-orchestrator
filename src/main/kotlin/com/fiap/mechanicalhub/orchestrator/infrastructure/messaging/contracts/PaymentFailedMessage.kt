package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts

import java.time.Instant
import java.util.UUID

/** `reason`: `EXPIRED` ou `FAILED` (td_payment_failed F3). */
data class PaymentFailedMessage(
    override val eventId: UUID,
    override val transactionId: UUID,
    override val orderId: UUID,
    override val version: Int,
    override val timestamp: Instant,
    val reason: String,
) : EventMessage {
    override val type = "paymentFailed"
}
