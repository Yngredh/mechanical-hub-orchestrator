package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts

import java.time.Instant
import java.util.UUID

data class StockReservedMessage(
    override val eventId: UUID,
    override val transactionId: UUID,
    override val orderId: UUID,
    override val version: Int,
    override val timestamp: Instant,
    val reservedMaterials: List<ReservedMaterialDto>,
) : EventMessage {
    override val type = "stockReserved"
}
