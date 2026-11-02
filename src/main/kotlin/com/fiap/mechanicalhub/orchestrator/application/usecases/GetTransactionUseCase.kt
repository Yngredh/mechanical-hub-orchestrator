package com.fiap.mechanicalhub.orchestrator.application.usecases

import com.fiap.mechanicalhub.orchestrator.application.ports.SagaRepository
import com.fiap.mechanicalhub.orchestrator.application.ports.TransactionHistoryQueryPort
import java.util.UUID

/** Consulta operacional: `transaction_register` + `transaction_history` de uma ordem. */
class GetTransactionUseCase(
    private val sagaRepository: SagaRepository,
    private val historyQuery: TransactionHistoryQueryPort,
) {
    /** `null` quando nao ha saga para a ordem. */
    fun execute(orderId: UUID): TransactionView? =
        sagaRepository.findByOrderId(orderId)?.let { saga ->
            TransactionView(saga, historyQuery.findByOrderId(orderId))
        }
}
