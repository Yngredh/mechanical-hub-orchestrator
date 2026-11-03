package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.events

/** O que o listener faz com a mensagem depois do processamento. */
sealed interface EventDecision {
    /** `commitTransaction` feito, duplicata ou descarte pela guarda: apagar a mensagem. */
    data object Acknowledge : EventDecision

    /** Erro transitorio: nao apagar; nova tentativa apos `delaySeconds`. */
    data class Retry(
        val delaySeconds: Int,
        val error: Throwable,
    ) : EventDecision

    /** Erro permanente: enviar a DLQ e apagar da fila de origem. */
    data class DeadLetter(
        val error: Throwable,
    ) : EventDecision
}
