package com.fiap.mechanicalhub.orchestrator.logging

import java.math.BigDecimal

/** Montagem da mensagem logfmt e higienização dos valores (logging-standard §1 e §6). */
internal object LogFormat {
    private const val OWN_PACKAGE = "com.fiap.mechanicalhub"
    private const val MAX_TEXT = 300
    private const val MAX_CAUSES = 10
    private val WHITESPACE = Regex("\\s+")

    fun format(fields: FlowFields): Pair<String, List<Any?>> {
        val keys = mutableListOf<String>()
        val arguments = mutableListOf<Any?>()
        val plain =
            listOf(
                "orderId" to fields.orderId,
                "status" to fields.status,
                "stage" to fields.stage,
                "attempt" to fields.attempt,
                "durationMs" to fields.durationMs,
            ) + fields.context
        plain.forEach { (key, value) ->
            render(value)?.let {
                keys += "$key={}"
                arguments += it
            }
        }
        keys += "msg=\"{}\""
        arguments += quote(fields.msg)
        fields.error?.let { error ->
            keys += "ex={}"
            arguments += error.javaClass.simpleName
            keys += "exMsg=\"{}\""
            arguments += quote(safeMessage(error))
        }
        return keys.joinToString(" ") to arguments
    }

    /** Valores sem espaço; listas separadas por vírgula; nulo e lista vazia omitem a chave. */
    private fun render(value: Any?): String? =
        when (value) {
            null -> null
            is BigDecimal -> value.toPlainString()
            is Collection<*> -> value.filterNotNull().joinToString(",") { clean(it.toString()) }.ifEmpty { null }
            else -> clean(value.toString()).ifEmpty { null }
        }

    private fun clean(text: String): String = text.replace(WHITESPACE, "_").replace('"', '\'')

    private fun quote(text: String): String = text.replace(WHITESPACE, " ").replace('"', '\'').take(MAX_TEXT)

    /**
     * Mensagem de exceção sem dados de terceiros: usa a primeira mensagem de uma exceção do próprio serviço na
     * cadeia de causas; exceção só de biblioteca externa vira um texto próprio com o nome da classe (§6).
     */
    private fun safeMessage(error: Throwable): String =
        generateSequence(error) { it.cause }
            .take(MAX_CAUSES)
            .firstOrNull { it.javaClass.name.startsWith(OWN_PACKAGE) && !it.message.isNullOrBlank() }
            ?.message
            ?: "Falha em ${error.javaClass.simpleName}"
}
