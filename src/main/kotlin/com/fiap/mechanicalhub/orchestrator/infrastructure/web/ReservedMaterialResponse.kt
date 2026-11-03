package com.fiap.mechanicalhub.orchestrator.infrastructure.web

import java.util.UUID

data class ReservedMaterialResponse(
    val materialId: UUID,
    val quantity: Int,
)
