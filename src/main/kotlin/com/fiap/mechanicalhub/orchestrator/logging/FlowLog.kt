package com.fiap.mechanicalhub.orchestrator.logging

import org.slf4j.Logger
import org.slf4j.MDC
import java.time.Clock

/**
 * Logs de etapa da transação distribuída. A borda que recebe a mensagem ou a requisição (listener SQS, controller,
 * webhook, job) abre a etapa com [begin], loga `START` e termina com `END` ou `FAIL`. Enquanto a etapa está aberta
 * ela fica associada à thread, e o caso de uso acrescenta campos de contexto e a `msg` do `END` com [detail] e
 * [endMessage], sem conhecer o logger da borda.
 */
object FlowLog {
    const val ORDER_ID_KEY = "orderId"

    private val current = ThreadLocal<FlowStep?>()
    private val CAMEL_HUMP = Regex("([a-z0-9])([A-Z])")

    /** Abre a etapa na thread atual. Quem abre loga `START` e garante o `END` ou o `FAIL`. */
    fun begin(
        log: Logger,
        orderId: Any?,
        status: String?,
        attempt: Int? = null,
        clock: Clock = Clock.systemUTC(),
    ): FlowStep = FlowStep(log, orderId?.toString(), status, attempt, clock).also { current.set(it) }

    /** Etapa aberta na thread atual, se houver. */
    fun current(): FlowStep? = current.get()

    /** Acrescenta campos de contexto (logging-standard §2.1) à etapa aberta; sem efeito fora de uma etapa. */
    fun detail(vararg fields: Pair<String, Any?>) {
        current.get()?.detail(fields.asList())
    }

    /** Define a `msg` do `END` da etapa aberta (o caso de uso sabe o que de fato aconteceu). */
    fun endMessage(msg: String) {
        current.get()?.endMessage(msg)
    }

    /** Ordem descoberta depois do `START` (webhook, job): passa a sair nas linhas seguintes e no MDC. */
    fun assignOrder(orderId: Any?) {
        current.get()?.assignOrder(orderId)
    }

    /** Linha avulsa da etapa aberta (mesmo `orderId` e `status`), sem `stage`: chamada externa, detalhe, alerta. */
    fun note(
        log: Logger,
        level: LogLevel,
        msg: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val step = current.get()
        val orderId = step?.orderId ?: MDC.get(ORDER_ID_KEY)
        write(log, level, FlowFields(orderId, step?.status, msg, context = fields.asList()))
    }

    /** Status do log a partir do tipo da mensagem: `generatePaymentLink` vira `GENERATE_PAYMENT_LINK` (§3). */
    fun statusOf(messageType: String): String = messageType.replace(CAMEL_HUMP, "\$1_\$2").uppercase()

    fun write(
        log: Logger,
        level: LogLevel,
        fields: FlowFields,
    ) {
        val builder =
            when (level) {
                LogLevel.DEBUG -> log.atDebug()
                LogLevel.INFO -> log.atInfo()
                LogLevel.WARN -> log.atWarn()
                LogLevel.ERROR -> log.atError()
            }
        val (format, arguments) = LogFormat.format(fields)
        builder.setMessage(format)
        arguments.forEach { builder.addArgument(it) }
        // Só a falha permanente leva a exceção: o encoder gera o stack_trace (logging-standard §8).
        if (level == LogLevel.ERROR) fields.error?.let { builder.setCause(it) }
        withOrderInMdc(fields.orderId?.toString()) { builder.log() }
    }

    /**
     * O `orderId` do JSON (MDC) precisa ser o mesmo da mensagem: com valores diferentes o `| logfmt` do Loki
     * renomeia o campo extraído. Linhas sobre outra ordem (ex.: pendência de outra ordem resolvida por uma
     * devolução) trocam o MDC só durante a escrita.
     */
    private fun withOrderInMdc(
        orderId: String?,
        block: () -> Unit,
    ) {
        val previous = MDC.get(ORDER_ID_KEY)
        if (orderId == null || orderId == previous) return block()
        MDC.put(ORDER_ID_KEY, orderId)
        try {
            block()
        } finally {
            if (previous == null) MDC.remove(ORDER_ID_KEY) else MDC.put(ORDER_ID_KEY, previous)
        }
    }

    internal fun release(step: FlowStep) {
        if (current.get() === step) current.remove()
    }
}
