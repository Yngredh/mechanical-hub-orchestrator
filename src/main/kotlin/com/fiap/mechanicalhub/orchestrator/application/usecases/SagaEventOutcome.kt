package com.fiap.mechanicalhub.orchestrator.application.usecases

import com.fiap.mechanicalhub.orchestrator.domain.model.SagaAdvance

sealed interface SagaEventOutcome {
    data class Applied(
        val advance: SagaAdvance,
    ) : SagaEventOutcome

    data class Discarded(
        val result: SagaStepResult.Discarded,
    ) : SagaEventOutcome

    /** `messageKey` ja registrado na `transaction_history`: nada e reaplicado. */
    data object Duplicate : SagaEventOutcome
}
