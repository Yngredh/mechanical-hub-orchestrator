package com.fiap.mechanicalhub.orchestrator.application.ports

/** Mensagem ja registrada: o indice unico de `messageKey` rejeitou o insert (`E11000`). */
class DuplicateMessageException(
    messageKey: String,
    cause: Throwable? = null,
) : RuntimeException("Mensagem $messageKey ja processada", cause)
