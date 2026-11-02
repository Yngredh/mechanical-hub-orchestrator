package com.fiap.mechanicalhub.orchestrator.application.usecases

import com.fiap.mechanicalhub.orchestrator.domain.model.SagaEvent
import java.util.UUID

/** Evento recebido da fila, ja convertido; `eventId` e o `messageKey` da deduplicacao. */
data class IncomingSagaEvent(
    val eventId: UUID,
    val event: SagaEvent,
)
