package com.fiap.mechanicalhub.orchestrator.application.usecases

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum

/** Evento sem handler registrado: erro permanente (vai para a DLQ). */
class UnsupportedSagaEventException(
    eventType: SagaEventTypeEnum,
) : RuntimeException("Nenhum handler registrado para o evento ${eventType.messageType}")
