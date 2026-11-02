package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.events

/** Mensagem fora do contrato (JSON invalido, `type` desconhecido, campo ausente ou valor invalido): erro permanente. */
class InvalidMessageException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
