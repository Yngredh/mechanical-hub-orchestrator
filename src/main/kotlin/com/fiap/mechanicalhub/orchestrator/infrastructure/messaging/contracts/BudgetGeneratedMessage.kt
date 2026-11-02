package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class BudgetGeneratedMessage(
    override val eventId: UUID,
    override val transactionId: UUID,
    override val orderId: UUID,
    override val version: Int,
    override val timestamp: Instant,
    val budgetId: UUID,
    val orderCost: BigDecimal,
) : EventMessage {
    override val type = "budgetGenerated"
}
