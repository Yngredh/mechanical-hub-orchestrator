package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts

import java.time.Instant
import java.util.UUID

data class ReleaseOrderMessage(
    override val eventId: UUID,
    override val transactionId: UUID,
    override val orderId: UUID,
    override val timestamp: Instant,
    override val idempotencyKey: String,
    override val version: Int = CONTRACT_VERSION,
) : CommandMessage {
    override val type = "releaseOrder"
}
