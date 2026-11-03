package com.fiap.mechanicalhub.orchestrator.domain.model

import java.util.UUID

/** Reparo recebido em `diagnosisCompleted`. */
data class RepairReference(
    val repairId: UUID,
)
