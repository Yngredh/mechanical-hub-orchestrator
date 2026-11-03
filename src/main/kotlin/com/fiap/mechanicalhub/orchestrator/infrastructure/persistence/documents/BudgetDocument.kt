package com.fiap.mechanicalhub.orchestrator.infrastructure.persistence.documents

import org.springframework.data.mongodb.core.mapping.Field
import org.springframework.data.mongodb.core.mapping.FieldType
import java.math.BigDecimal

data class BudgetDocument(
    val budgetId: String,
    @Field(targetType = FieldType.DECIMAL128) val orderCost: BigDecimal,
)
