package com.fiap.mechanicalhub.orchestrator.infrastructure.persistence.documents

import java.time.Instant

data class PaymentDocument(
    val link: String?,
    val expiresAt: Instant?,
    val paymentId: String?,
)
