package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts

import java.time.Instant
import java.util.UUID

/** `reason = PAYMENT_NOT_CONFIRMED` (TD_paymentFailed F4). */
data class CancelOrderMessage(
    override val eventId: UUID,
    override val transactionId: UUID,
    override val orderId: UUID,
    override val timestamp: Instant,
    override val idempotencyKey: String,
    val reason: String,
    override val version: Int = CONTRACT_VERSION,
) : CommandMessage {
    override val type = "cancelOrder"
}
