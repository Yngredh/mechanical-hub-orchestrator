package com.fiap.mechanicalhub.orchestrator.application.ports

import java.time.Instant

/** Comando `PENDING` da `outbox_events`; `payload` e o corpo JSON pronto para a fila. */
data class OutboxMessage(
    val eventId: String,
    val orderId: String,
    val seq: Long,
    val eventType: String,
    val payload: String,
    val attempts: Int,
    val createdAt: Instant,
)
