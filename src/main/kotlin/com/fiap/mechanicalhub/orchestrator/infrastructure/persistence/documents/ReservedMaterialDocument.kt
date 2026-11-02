package com.fiap.mechanicalhub.orchestrator.infrastructure.persistence.documents

data class ReservedMaterialDocument(
    val materialId: String,
    val quantity: Int,
)
