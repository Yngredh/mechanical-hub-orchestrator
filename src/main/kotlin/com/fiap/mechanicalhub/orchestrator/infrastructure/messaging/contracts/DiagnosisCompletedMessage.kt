package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts

import java.time.Instant
import java.util.UUID

/** Publicado pelo `orders`; ainda nao ha `transactionId` (a saga nasce deste evento). */
data class DiagnosisCompletedMessage(
    override val eventId: UUID,
    override val orderId: UUID,
    override val version: Int,
    override val timestamp: Instant,
    val repairs: List<RepairReferenceDto>,
    override val transactionId: UUID? = null,
) : EventMessage {
    override val type = "diagnosisCompleted"
}
