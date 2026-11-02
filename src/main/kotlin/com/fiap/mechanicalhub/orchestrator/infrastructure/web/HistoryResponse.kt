package com.fiap.mechanicalhub.orchestrator.infrastructure.web

import java.time.Instant

data class HistoryResponse(
    val seq: Long?,
    val messageType: String,
    val messageKey: String,
    val stepBefore: String?,
    val stepAfter: String?,
    val outcome: String,
    val receivedAt: Instant,
)
