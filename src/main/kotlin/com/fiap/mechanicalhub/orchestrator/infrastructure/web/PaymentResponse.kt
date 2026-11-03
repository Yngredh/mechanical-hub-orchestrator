package com.fiap.mechanicalhub.orchestrator.infrastructure.web

import java.time.Instant

data class PaymentResponse(
    val link: String?,
    val expiresAt: Instant?,
    val paymentId: String?,
)
