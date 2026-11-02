package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class SendApprovalMessage(
    override val eventId: UUID,
    override val transactionId: UUID,
    override val orderId: UUID,
    override val timestamp: Instant,
    override val idempotencyKey: String,
    val budgetId: UUID,
    val orderCost: BigDecimal,
    override val version: Int = CONTRACT_VERSION,
) : CommandMessage {
    override val type = "sendApproval"
}
