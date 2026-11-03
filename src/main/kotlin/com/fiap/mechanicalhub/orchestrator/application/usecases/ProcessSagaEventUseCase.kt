package com.fiap.mechanicalhub.orchestrator.application.usecases

import com.fiap.mechanicalhub.orchestrator.application.ports.OutboxPort
import com.fiap.mechanicalhub.orchestrator.application.ports.TransactionHistoryEntry
import com.fiap.mechanicalhub.orchestrator.application.ports.TransactionHistoryPort
import com.fiap.mechanicalhub.orchestrator.domain.enums.TransactionOutcomeEnum
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * Pipeline transacional de cada mensagem (spec secao 3, td_finished_os §0.4),
 * numa unica transacao multi-documento: deduplicacao por `messageKey` ->
 * handler (guarda de passo + atualizacao da saga) -> `transaction_history` ->
 * `outbox_events` -> `commitTransaction`. Descarte pela guarda grava so o
 * historico (`DISCARDED_STEP_GUARD`), sem alteracao da saga nem outbox.
 *
 * Duplicata detectada so no insert do historico (corrida) lanca
 * `DuplicateMessageException` e a transacao inteira e desfeita.
 */
@Service
class ProcessSagaEventUseCase(
    private val dispatcher: SagaEventDispatcher,
    private val transactionHistory: TransactionHistoryPort,
    private val outbox: OutboxPort,
    private val clock: Clock,
) {
    @Transactional
    fun execute(incoming: IncomingSagaEvent): SagaEventOutcome {
        val messageKey = incoming.eventId.toString()
        if (transactionHistory.existsByMessageKey(messageKey)) return SagaEventOutcome.Duplicate

        val now = clock.instant()
        val event = incoming.event
        return when (val result = dispatcher.handlerFor(event.type).handle(event, now)) {
            is SagaStepResult.Applied -> {
                val advance = result.advance
                transactionHistory.append(
                    historyEntry(incoming, now, TransactionOutcomeEnum.APPLIED).copy(
                        transactionId = advance.saga.transactionId,
                        seq = advance.saga.seq,
                        stepBefore = advance.stepBefore,
                        stepAfter = advance.stepAfter,
                    ),
                )
                outbox.append(advance.commands, now)
                SagaEventOutcome.Applied(advance)
            }

            is SagaStepResult.Discarded -> {
                transactionHistory.append(
                    historyEntry(incoming, now, TransactionOutcomeEnum.DISCARDED_STEP_GUARD).copy(
                        transactionId = result.transactionId,
                        seq = result.seq,
                        stepBefore = result.currentStep,
                        stepAfter = result.currentStep,
                    ),
                )
                SagaEventOutcome.Discarded(result)
            }
        }
    }

    private fun historyEntry(
        incoming: IncomingSagaEvent,
        now: Instant,
        outcome: TransactionOutcomeEnum,
    ) = TransactionHistoryEntry(
        transactionId = null,
        orderId = incoming.event.orderId,
        seq = null,
        messageType = incoming.event.type.messageType,
        messageKey = incoming.eventId.toString(),
        stepBefore = null,
        stepAfter = null,
        outcome = outcome,
        receivedAt = now,
    )
}
