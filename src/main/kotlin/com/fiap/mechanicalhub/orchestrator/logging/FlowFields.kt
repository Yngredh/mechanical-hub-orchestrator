package com.fiap.mechanicalhub.orchestrator.logging

/**
 * Campos de uma linha no padrão de `engineering/logging-standard.md`. A mensagem sai em logfmt, montada com
 * placeholders `{}` na ordem `orderId status stage attempt durationMs <contexto> msg ex exMsg`; chaves sem valor
 * são omitidas.
 */
data class FlowFields(
    val orderId: Any?,
    val status: String?,
    val msg: String,
    val stage: Stage? = null,
    val attempt: Int? = null,
    val durationMs: Long? = null,
    val context: List<Pair<String, Any?>> = emptyList(),
    val error: Throwable? = null,
)
