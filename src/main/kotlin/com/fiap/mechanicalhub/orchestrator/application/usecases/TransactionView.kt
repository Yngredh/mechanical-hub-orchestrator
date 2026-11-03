package com.fiap.mechanicalhub.orchestrator.application.usecases

import com.fiap.mechanicalhub.orchestrator.application.ports.TransactionHistoryEntry
import com.fiap.mechanicalhub.orchestrator.domain.model.Saga

/** Transacao da saga com a trilha completa. */
data class TransactionView(
    val saga: Saga,
    val history: List<TransactionHistoryEntry>,
)
