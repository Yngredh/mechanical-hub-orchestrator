package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.events

import com.fiap.mechanicalhub.orchestrator.application.usecases.IncomingSagaEvent
import com.fiap.mechanicalhub.orchestrator.application.usecases.ProcessSagaEventUseCase
import com.fiap.mechanicalhub.orchestrator.application.usecases.SagaEventOutcome
import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaStatusEnum
import com.fiap.mechanicalhub.orchestrator.domain.model.PaymentFailed
import com.fiap.mechanicalhub.orchestrator.domain.model.SagaResponseEvent
import com.fiap.mechanicalhub.orchestrator.infrastructure.observability.LogContext
import com.fiap.mechanicalhub.orchestrator.infrastructure.observability.MessageOutcome
import com.fiap.mechanicalhub.orchestrator.infrastructure.observability.SagaMetrics
import com.fiap.mechanicalhub.orchestrator.logging.FlowLog
import com.fiap.mechanicalhub.orchestrator.logging.FlowStep
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** O que o listener faz com a mensagem depois do processamento. */
sealed interface EventDecision {
    /** `commitTransaction` feito, duplicata ou descarte pela guarda: apagar a mensagem. */
    data object Acknowledge : EventDecision

    /** Erro transitorio: nao apagar; nova tentativa apos `delaySeconds`. */
    data class Retry(
        val delaySeconds: Int,
        val error: Throwable,
    ) : EventDecision

    /** Erro permanente: enviar a DLQ e apagar da fila de origem. */
    data class DeadLetter(
        val error: Throwable,
    ) : EventDecision
}

/**
 * Parse -> caso de uso (transacao) -> decisao, isolado do SDK para ser testado
 * sem SQS. Retry bloqueante por `ApproximateReceiveCount`: 1 s, 2 s, 4 s, 8 s,
 * 8 s (failure_handling.md §2.2); o 7o recebimento vai sozinho para a DLQ
 * (`maxReceiveCount = 6`). Registra as metricas `saga_*` e coloca `eventId`,
 * `orderId`, `transactionId` e `currentStep` no MDC durante o processamento.
 *
 * Cada evento e uma etapa do log (logging-standard §4) com o `status` do
 * comando que o orquestrador publica em resposta (`stockReserved` ->
 * `GENERATE_BUDGET`): `START` ao receber, `END` com a decisao da saga
 * (avancou, descartado pela guarda, duplicado) e `FAIL` com WARN (vai ser
 * reprocessado) ou ERROR (vai para a DLQ).
 */
@Component
class SagaEventMessageProcessor(
    private val parser: SagaEventParser,
    private val processSagaEvent: ProcessSagaEventUseCase,
    private val metrics: SagaMetrics,
) {
    fun process(
        body: String,
        receiveCount: Int,
    ): EventDecision {
        val parsed = runCatching { parser.parse(body) }
        val incoming = parsed.getOrNull()
        val type = incoming?.event?.type?.messageType ?: UNKNOWN_TYPE
        val messageType = incoming?.event?.type?.messageType ?: parser.fieldOrNull(body, "type")
        val orderId = incoming?.event?.orderId ?: parser.fieldOrNull(body, LogContext.ORDER_ID)
        return LogContext.with(
            LogContext.EVENT_ID to (incoming?.eventId ?: parser.fieldOrNull(body, LogContext.EVENT_ID)),
            LogContext.ORDER_ID to orderId,
            LogContext.TRANSACTION_ID to (incoming?.event as? SagaResponseEvent)?.transactionId,
            LogContext.CURRENT_STEP to null,
        ) {
            val step = FlowLog.begin(log, orderId, statusForType(messageType), receiveCount)
            step.start(messageType?.let { START_MESSAGES[it] } ?: "Evento recebido", "eventType" to messageType)
            parsed
                .mapCatching { processSagaEvent.execute(it) }
                .fold(
                    onSuccess = { decide(step, type, incoming, it) },
                    onFailure = { error -> decideOnError(step, type, error, receiveCount) },
                )
        }
    }

    /** `status` do log do evento, para a linha do envio a DLQ. */
    fun statusOf(body: String): String? = statusForType(parser.fieldOrNull(body, "type"))

    private fun statusForType(messageType: String?): String? = messageType?.let { EVENT_STATUS[it] ?: FlowLog.statusOf(it) }

    private fun decide(
        step: FlowStep,
        type: String,
        incoming: IncomingSagaEvent?,
        outcome: SagaEventOutcome,
    ): EventDecision {
        when (outcome) {
            is SagaEventOutcome.Applied -> {
                val saga = outcome.advance.saga
                LogContext.put(LogContext.TRANSACTION_ID, saga.transactionId)
                LogContext.put(LogContext.CURRENT_STEP, saga.currentStep)
                if (outcome.advance.stepBefore == null) metrics.sagaStarted()
                if (saga.status.terminal) metrics.sagaFinished(saga.status)
                metrics.messageProcessed(type, MessageOutcome.APPLIED)
                logApplied(step, outcome, (incoming?.event as? PaymentFailed)?.reason)
            }

            is SagaEventOutcome.Discarded -> {
                LogContext.put(LogContext.CURRENT_STEP, outcome.result.currentStep)
                metrics.messageProcessed(type, MessageOutcome.DISCARDED_STEP_GUARD)
                // Nao e erro (failure_handling §1): passo atual diferente do esperado (duplicata ou fora de ordem).
                step.end(
                    "Evento descartado pela guarda de passo",
                    "outcome" to MessageOutcome.DISCARDED_STEP_GUARD,
                    "transactionId" to outcome.result.transactionId,
                    "currentStep" to outcome.result.currentStep,
                )
            }

            SagaEventOutcome.Duplicate -> {
                metrics.messageProcessed(type, MessageOutcome.DUPLICATE)
                step.end("Evento duplicado ignorado (transaction_history)", "outcome" to MessageOutcome.DUPLICATE)
            }
        }
        return EventDecision.Acknowledge
    }

    /** `END` da saga que avancou: passos antes/depois, status da saga e comandos gravados no outbox. */
    private fun logApplied(
        step: FlowStep,
        outcome: SagaEventOutcome.Applied,
        paymentFailureReason: Any?,
    ) {
        val advance = outcome.advance
        val saga = advance.saga
        val commands = advance.commands.map { it.type.messageType }
        val joined = commands.joinToString()
        val msg =
            when {
                saga.status == SagaStatusEnum.COMPLETED -> "Saga concluida"
                saga.status == SagaStatusEnum.COMPENSATED -> "Saga compensada"
                advance.stepBefore == null -> "Saga criada, comando $joined gravado no outbox"
                saga.status == SagaStatusEnum.COMPENSATING -> "Compensacao em andamento, comando $joined gravado no outbox"
                commands.isNotEmpty() -> "Saga avancou, comando $joined gravado no outbox"
                else -> "Saga avancou"
            }
        step.end(
            msg,
            "transactionId" to saga.transactionId,
            "sagaStatus" to saga.status,
            "previousStep" to advance.stepBefore,
            "currentStep" to advance.stepAfter,
            "commands" to commands,
            "reason" to paymentFailureReason,
        )
    }

    private fun decideOnError(
        step: FlowStep,
        type: String,
        error: Throwable,
        receiveCount: Int,
    ): EventDecision =
        when (EventErrorClassifier.classify(error)) {
            EventErrorClass.DUPLICATE -> {
                metrics.messageProcessed(type, MessageOutcome.DUPLICATE)
                step.end("Evento duplicado ignorado (E11000 em transaction_history.messageKey)", "outcome" to MessageOutcome.DUPLICATE)
                EventDecision.Acknowledge
            }

            EventErrorClass.TRANSIENT -> {
                val delay = backoffSeconds(receiveCount)
                metrics.messageProcessed(type, MessageOutcome.RETRY)
                step.warnFail("Falha transitoria no evento, sera reprocessado", error, "retryInSeconds" to delay)
                EventDecision.Retry(delay, error)
            }

            EventErrorClass.PERMANENT -> {
                metrics.messageProcessed(type, MessageOutcome.DEAD_LETTER)
                step.errorFail("Falha permanente no evento, sera enviado para a DLQ", error)
                EventDecision.DeadLetter(error)
            }
        }

    private companion object {
        const val UNKNOWN_TYPE = "unknown"
        val BACKOFF_SECONDS = listOf(1, 2, 4, 8, 8)
        val log = LoggerFactory.getLogger(SagaEventMessageProcessor::class.java)

        /** Evento recebido -> `status` do comando que o orquestrador publica (ou do passo que ele encerra). */
        val EVENT_STATUS =
            mapOf(
                "diagnosisCompleted" to "RESERVE_STOCK",
                "stockPending" to "MARK_STOCK_PENDING",
                "stockReserved" to "GENERATE_BUDGET",
                "budgetGenerated" to "SEND_APPROVAL",
                "orderApproved" to "GENERATE_PAYMENT_LINK",
                "orderRejected" to "RESTORE_STOCK",
                "paymentLinkGenerated" to "SEND_PAYMENT_LINK",
                "paymentConfirmed" to "RELEASE_ORDER",
                "paymentFailed" to "CANCEL_ORDER",
                "orderCanceled" to "RESTORE_STOCK",
                "orderFinished" to "CONSUME_STOCK",
                "stockConsumed" to "CONSUME_STOCK",
                "stockRestored" to "RESTORE_STOCK",
            )

        val START_MESSAGES =
            mapOf(
                "diagnosisCompleted" to "Diagnostico concluido, iniciando a saga",
                "stockPending" to "Pendencia de estoque recebida, saga aguardando estoque",
                "stockReserved" to "Estoque reservado, solicitando orcamento",
                "budgetGenerated" to "Orcamento gerado, solicitando aprovacao do cliente",
                "orderApproved" to "Aprovacao recebida, solicitando link de pagamento",
                "orderRejected" to "Orcamento recusado, iniciando compensacao",
                "paymentLinkGenerated" to "Link de pagamento gerado, enviando ao cliente",
                "paymentConfirmed" to "Pagamento confirmado, liberando a ordem",
                "paymentFailed" to "Pagamento nao confirmado, iniciando compensacao",
                "orderCanceled" to "Ordem cancelada, solicitando devolucao do estoque",
                "orderFinished" to "Ordem finalizada, solicitando baixa do estoque",
                "stockConsumed" to "Baixa do estoque confirmada, encerrando a saga",
                "stockRestored" to "Devolucao do estoque confirmada, encerrando a compensacao",
            )

        fun backoffSeconds(receiveCount: Int): Int = BACKOFF_SECONDS[(receiveCount - 1).coerceIn(BACKOFF_SECONDS.indices)]
    }
}
