package com.fiap.mechanicalhub.orchestrator.domain.model

import java.time.Instant

/** `link` e `expiresAt` chegam em `paymentLinkGenerated`; `paymentId` em `paymentConfirmed`. */
data class Payment(
    val link: String? = null,
    val expiresAt: Instant? = null,
    val paymentId: String? = null,
)
