package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaStepEnum
import com.fiap.mechanicalhub.orchestrator.domain.statemachine.SagaTransition

/** Resultado de uma transicao aplicada: dados para `transaction_history` (`stepBefore`/`stepAfter`) e `outbox_events`. */
data class SagaAdvance(
    val transition: SagaTransition,
    val stepBefore: SagaStepEnum?,
    val saga: Saga,
    val commands: List<SagaCommand>,
) {
    val stepAfter: SagaStepEnum? get() = saga.currentStep
}
