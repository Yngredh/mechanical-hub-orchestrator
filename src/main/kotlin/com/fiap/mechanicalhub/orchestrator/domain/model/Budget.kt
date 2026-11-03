package com.fiap.mechanicalhub.orchestrator.domain.model

import java.math.BigDecimal
import java.util.UUID

/** Orcamento recebido em `budgetGenerated`. */
data class Budget(
    val budgetId: UUID,
    val orderCost: BigDecimal,
)
