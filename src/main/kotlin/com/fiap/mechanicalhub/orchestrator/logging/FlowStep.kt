package com.fiap.mechanicalhub.orchestrator.logging

import org.slf4j.Logger
import org.slf4j.MDC
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Etapa em andamento: guarda o instante do `START` (para o `durationMs`) e o contexto acumulado até o `END`. */
class FlowStep internal constructor(
    private val log: Logger,
    orderId: String?,
    val status: String?,
    val attempt: Int?,
    private val clock: Clock,
) {
    var orderId: String? = orderId
        private set

    private val startedAt: Instant = clock.instant()
    private val context = LinkedHashMap<String, Any?>()
    private var endMsg: String? = null

    fun assignOrder(orderId: Any?) {
        if (orderId == null) return
        this.orderId = orderId.toString()
        MDC.put(FlowLog.ORDER_ID_KEY, orderId.toString())
    }

    fun detail(fields: List<Pair<String, Any?>>): FlowStep {
        fields.forEach { (key, value) -> context[key] = value }
        return this
    }

    fun endMessage(msg: String): FlowStep {
        endMsg = msg
        return this
    }

    /** `START`; os campos informados aqui também saem no `END`/`FAIL` da etapa. */
    fun start(
        msg: String,
        vararg fields: Pair<String, Any?>,
    ) {
        detail(fields.asList())
        emit(LogLevel.INFO, Stage.START, msg, fields.asList(), null)
    }

    /** `END` com o contexto acumulado; a `msg` definida pelo caso de uso tem prioridade sobre [defaultMsg]. */
    fun end(
        defaultMsg: String,
        vararg fields: Pair<String, Any?>,
    ) {
        detail(fields.asList())
        emit(LogLevel.INFO, Stage.END, endMsg ?: defaultMsg, context.toList(), null)
        FlowLog.release(this)
    }

    /** `FAIL` que vai ser tentado de novo (ou rejeição que não exige intervenção): WARN, sem stack trace. */
    fun warnFail(
        msg: String,
        error: Throwable?,
        vararg fields: Pair<String, Any?>,
    ) {
        detail(fields.asList())
        emit(LogLevel.WARN, Stage.FAIL, msg, context.toList(), error)
        FlowLog.release(this)
    }

    /** `FAIL` permanente (DLQ, alerta, intervenção): ERROR com stack trace. */
    fun errorFail(
        msg: String,
        error: Throwable?,
        vararg fields: Pair<String, Any?>,
    ) {
        detail(fields.asList())
        emit(LogLevel.ERROR, Stage.FAIL, msg, context.toList(), error)
        FlowLog.release(this)
    }

    fun elapsedMs(): Long = Duration.between(startedAt, clock.instant()).toMillis()

    private fun emit(
        level: LogLevel,
        stage: Stage,
        msg: String,
        fields: List<Pair<String, Any?>>,
        error: Throwable?,
    ) {
        val duration = if (stage == Stage.START) null else elapsedMs()
        FlowLog.write(log, level, FlowFields(orderId, status, msg, stage, attempt, duration, fields, error))
    }
}
