package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.events

enum class EventErrorClass {
    /** `E11000` em `messageKey` numa corrida: a mensagem ja foi processada (nao e erro). */
    DUPLICATE,
    TRANSIENT,
    PERMANENT,
}
