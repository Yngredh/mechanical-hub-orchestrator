package com.fiap.mechanicalhub.orchestrator.domain.model

import java.util.UUID

/** Material reservado recebido em `stockReserved`; usado no `consumeStock` e no `restoreStock`. */
data class ReservedMaterial(
    val materialId: UUID,
    val quantity: Int,
)
