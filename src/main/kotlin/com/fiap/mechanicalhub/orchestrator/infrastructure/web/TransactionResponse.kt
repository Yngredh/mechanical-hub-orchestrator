package com.fiap.mechanicalhub.orchestrator.infrastructure.web

data class TransactionResponse(
    val transaction: SagaResponse,
    val history: List<HistoryResponse>,
)
