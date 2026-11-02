package com.fiap.mechanicalhub.orchestrator.application.ports

import com.fiap.mechanicalhub.orchestrator.domain.enums.CommandDestinationEnum

/** Envio de um comando a fila do servico de destino. */
interface CommandPublisher {
    fun publish(
        destination: CommandDestinationEnum,
        message: OutboxMessage,
    )
}
