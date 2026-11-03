package com.fiap.mechanicalhub.orchestrator.application.ports

import java.time.Instant

/** Leitura e marcacao da `outbox_events` feitas pelo relay. */
interface OutboxRelayStore {
    /** Os `limit` mais antigos `PENDING`, por `createdAt` e, dentro dele, `seq`. */
    fun findPending(limit: Int): List<OutboxMessage>

    fun markSent(
        eventId: String,
        sentAt: Instant,
    )

    fun markFailed(
        eventId: String,
        error: String,
    )
}
