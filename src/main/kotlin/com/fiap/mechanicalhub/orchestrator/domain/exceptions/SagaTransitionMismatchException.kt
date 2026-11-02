package com.fiap.mechanicalhub.orchestrator.domain.exceptions

/** Transicao usada com um evento de outro tipo ou com um passo que nao e o esperado. */
class SagaTransitionMismatchException(
    detail: String,
) : SagaDomainException(detail)
