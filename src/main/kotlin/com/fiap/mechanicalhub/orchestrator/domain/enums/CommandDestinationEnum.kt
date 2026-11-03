package com.fiap.mechanicalhub.orchestrator.domain.enums

/** Servico que recebe o comando; define a fila de destino no relay. */
enum class CommandDestinationEnum {
    ORDERS,
    STOCK,
    BILLING,
}
