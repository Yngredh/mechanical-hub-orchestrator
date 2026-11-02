package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts

import java.util.UUID

data class ReservedMaterialDto(
    val materialId: UUID,
    val quantity: Int,
)
