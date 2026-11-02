package com.fiap.mechanicalhub.orchestrator.infrastructure.observability

/** Resultado do processamento de uma mensagem (label `outcome` de `saga_messages_total`). */
enum class MessageOutcome {
    APPLIED,
    DISCARDED_STEP_GUARD,
    DUPLICATE,
    RETRY,
    DEAD_LETTER,
}
