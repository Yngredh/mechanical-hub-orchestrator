package com.fiap.mechanicalhub.orchestrator.application.usecases

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum
import org.springframework.stereotype.Component

/** Dispatcher por `type` -> handler. No maximo um handler por tipo de evento. */
@Component
class SagaEventDispatcher(
    handlers: List<SagaEventHandler>,
) {
    private val handlersByType: Map<SagaEventTypeEnum, SagaEventHandler> =
        handlers.associateBy(SagaEventHandler::eventType).also { byType ->
            require(byType.size == handlers.size) { "Mais de um handler registrado para o mesmo tipo de evento" }
        }

    fun handlerFor(eventType: SagaEventTypeEnum): SagaEventHandler =
        handlersByType[eventType] ?: throw UnsupportedSagaEventException(eventType)
}
