package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant
import java.util.UUID

/**
 * Copia local dos contratos dos eventos consumidos de
 * `mechanical-hub-orchestrator-events.fifo` (spec secao 3, "Consome").
 * Envelope e payload ficam no mesmo nivel do JSON; o `type` escolhe a classe.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = DiagnosisCompletedMessage::class, name = "diagnosisCompleted"),
    JsonSubTypes.Type(value = StockPendingMessage::class, name = "stockPending"),
    JsonSubTypes.Type(value = StockReservedMessage::class, name = "stockReserved"),
    JsonSubTypes.Type(value = BudgetGeneratedMessage::class, name = "budgetGenerated"),
    JsonSubTypes.Type(value = OrderApprovedMessage::class, name = "orderApproved"),
    JsonSubTypes.Type(value = OrderRejectedMessage::class, name = "orderRejected"),
    JsonSubTypes.Type(value = PaymentLinkGeneratedMessage::class, name = "paymentLinkGenerated"),
    JsonSubTypes.Type(value = PaymentConfirmedMessage::class, name = "paymentConfirmed"),
    JsonSubTypes.Type(value = PaymentFailedMessage::class, name = "paymentFailed"),
    JsonSubTypes.Type(value = OrderCanceledMessage::class, name = "orderCanceled"),
    JsonSubTypes.Type(value = OrderFinishedMessage::class, name = "orderFinished"),
    JsonSubTypes.Type(value = StockConsumedMessage::class, name = "stockConsumed"),
    JsonSubTypes.Type(value = StockRestoredMessage::class, name = "stockRestored"),
)
sealed interface EventMessage {
    val eventId: UUID
    val transactionId: UUID?
    val orderId: UUID
    val type: String
    val version: Int
    val timestamp: Instant
}
