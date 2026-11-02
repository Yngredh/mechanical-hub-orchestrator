package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant
import java.util.UUID

/**
 * Copia local dos contratos dos comandos publicados em
 * `mechanical-hub-{orders,stock,billing}-commands.fifo` (spec secao 3,
 * "Publica"). Envelope e payload no mesmo nivel do JSON; todo comando leva
 * `idempotencyKey = {transactionId}:{STEP}`.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = ReserveStockMessage::class, name = "reserveStock"),
    JsonSubTypes.Type(value = GenerateBudgetMessage::class, name = "generateBudget"),
    JsonSubTypes.Type(value = GeneratePaymentLinkMessage::class, name = "generatePaymentLink"),
    JsonSubTypes.Type(value = ConsumeStockMessage::class, name = "consumeStock"),
    JsonSubTypes.Type(value = RestoreStockMessage::class, name = "restoreStock"),
    JsonSubTypes.Type(value = SendApprovalMessage::class, name = "sendApproval"),
    JsonSubTypes.Type(value = MarkStockPendingMessage::class, name = "markStockPending"),
    JsonSubTypes.Type(value = ClearStockPendingMessage::class, name = "clearStockPending"),
    JsonSubTypes.Type(value = SendPaymentLinkMessage::class, name = "sendPaymentLink"),
    JsonSubTypes.Type(value = ReleaseOrderMessage::class, name = "releaseOrder"),
    JsonSubTypes.Type(value = CancelOrderMessage::class, name = "cancelOrder"),
)
sealed interface CommandMessage {
    val eventId: UUID
    val transactionId: UUID
    val orderId: UUID
    val type: String
    val version: Int
    val timestamp: Instant
    val idempotencyKey: String
}
