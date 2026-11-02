package com.fiap.mechanicalhub.orchestrator.infrastructure.web

import java.time.Instant
import java.util.UUID

data class SagaResponse(
    val transactionId: UUID,
    val orderId: UUID,
    val status: String,
    val currentStep: String?,
    val seq: Long,
    val repairs: List<UUID>,
    val reservedMaterials: List<ReservedMaterialResponse>,
    val budget: BudgetResponse?,
    val payment: PaymentResponse?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finishedAt: Instant?,
)
