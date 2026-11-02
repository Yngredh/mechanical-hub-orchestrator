package com.fiap.mechanicalhub.orchestrator.infrastructure.observability

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaStatusEnum
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * Contadores da saga: `saga_started_total`, `saga_finished_total{status}` e
 * `saga_messages_total{type, outcome}`. Os nomes sao o contrato com o painel
 * da saga do mechanical-hub-infra.
 */
@Component
class SagaMetrics(
    private val registry: MeterRegistry,
) {
    fun messageProcessed(
        type: String,
        outcome: MessageOutcome,
    ) {
        registry.counter("saga.messages", "type", type, "outcome", outcome.name).increment()
    }

    fun sagaStarted() {
        registry.counter("saga.started").increment()
    }

    fun sagaFinished(status: SagaStatusEnum) {
        registry.counter("saga.finished", "status", status.name).increment()
    }
}
