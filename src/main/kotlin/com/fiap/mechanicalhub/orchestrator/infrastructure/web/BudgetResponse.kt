package com.fiap.mechanicalhub.orchestrator.infrastructure.web

import java.math.BigDecimal
import java.util.UUID

data class BudgetResponse(
    val budgetId: UUID,
    val orderCost: BigDecimal,
)
