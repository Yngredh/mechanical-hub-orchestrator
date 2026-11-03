package com.fiap.mechanicalhub.orchestrator.application.usecases

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum
import com.fiap.mechanicalhub.orchestrator.domain.model.SagaEvent
import java.time.Instant

/**
 * Handler de um tipo de evento. Roda dentro da transacao aberta por
 * [ProcessSagaEventUseCase]; nao grava historico nem outbox (o pipeline grava).
 */
interface SagaEventHandler {
    val eventType: SagaEventTypeEnum

    fun handle(
        event: SagaEvent,
        now: Instant,
    ): SagaStepResult
}
