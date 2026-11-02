package com.fiap.mechanicalhub.orchestrator.domain.exceptions

import java.util.UUID

/** A saga nao tem um dado acumulado que o comando do passo exige (ex.: `budget` ausente no `sendApproval`). */
class SagaInconsistentStateException(
    transactionId: UUID,
    detail: String,
) : SagaDomainException("Saga $transactionId em estado inconsistente: $detail")
