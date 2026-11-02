package com.fiap.mechanicalhub.orchestrator.domain.exceptions

/** Base das excecoes de dominio da saga. */
sealed class SagaDomainException(
    message: String,
) : RuntimeException(message)
