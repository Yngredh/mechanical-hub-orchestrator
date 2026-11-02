package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaCommandTypeEnum
import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaStatusEnum
import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaStepEnum
import com.fiap.mechanicalhub.orchestrator.domain.exceptions.SagaInconsistentStateException
import com.fiap.mechanicalhub.orchestrator.domain.exceptions.SagaTransitionMismatchException
import com.fiap.mechanicalhub.orchestrator.domain.statemachine.SagaStateMachine
import com.fiap.mechanicalhub.orchestrator.domain.statemachine.SagaTransition
import java.time.Instant
import java.util.UUID

/**
 * Agregado da saga (documento de `transaction_register`). Acumula os dados de
 * cada passo e calcula o `seq` dos comandos emitidos: com `n` comandos numa
 * transicao, eles recebem `seq - n + 1 .. seq` do valor ja incrementado.
 */
data class Saga(
    val transactionId: UUID,
    val orderId: UUID,
    val status: SagaStatusEnum,
    val currentStep: SagaStepEnum?,
    val seq: Long,
    val repairs: List<RepairReference>,
    val reservedMaterials: List<ReservedMaterial> = emptyList(),
    val budget: Budget? = null,
    val payment: Payment? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finishedAt: Instant? = null,
) {
    val finished: Boolean get() = status.terminal

    /** Guarda de passo em memoria, equivalente ao filtro do `findOneAndUpdate`. */
    fun accepts(change: SagaChange): Boolean =
        currentStep == change.expectedStep && (!change.requiresPaymentLinkAbsent || payment?.link == null)

    /**
     * Aplica o evento pela maquina de estados, tentando as guardas candidatas
     * na ordem da tabela. Retorna `null` quando nenhuma aceita o passo atual
     * (descarte `DISCARDED_STEP_GUARD`).
     */
    fun apply(
        event: SagaResponseEvent,
        now: Instant,
    ): SagaAdvance? {
        if (event.transactionId != transactionId) {
            throw SagaTransitionMismatchException("Evento da transacao ${event.transactionId} aplicado a saga $transactionId")
        }
        val (transition, change) =
            SagaStateMachine
                .candidatesFor(event.type)
                .map { candidate -> candidate to SagaChange.of(candidate, event, now) }
                .firstOrNull { (_, candidateChange) -> accepts(candidateChange) }
                ?: return null
        val advanced = applyChange(change)
        return SagaAdvance(
            transition = transition,
            stepBefore = currentStep,
            saga = advanced,
            commands = advanced.commandsFor(transition),
        )
    }

    /** Estado depois da alteracao (o que o `findOneAndUpdate` com `returnDocument: after` devolve). */
    fun applyChange(change: SagaChange): Saga {
        if (!accepts(change)) {
            throw SagaTransitionMismatchException(
                "Saga $transactionId no passo $currentStep nao aceita a alteracao esperada em ${change.expectedStep}",
            )
        }
        return copy(
            status = change.status,
            currentStep = change.nextStep,
            seq = seq + change.seqIncrement,
            reservedMaterials = change.reservedMaterials ?: reservedMaterials,
            budget = change.budget ?: budget,
            payment = paymentAfter(change),
            updatedAt = change.updatedAt,
            finishedAt = change.finishedAt ?: finishedAt,
        )
    }

    /** Valores de `seq` reservados para os `count` ultimos comandos emitidos (`seq - count + 1 .. seq`). */
    fun emittedCommandSeqs(count: Int): LongRange = (seq - count + 1)..seq

    /** Comandos da transicao ja aplicada, com os dados lidos da saga e os `seq` reservados, na ordem de publicacao. */
    fun commandsFor(transition: SagaTransition): List<SagaCommand> =
        transition.commands
            .zip(emittedCommandSeqs(transition.seqIncrement).toList())
            .map { (type, commandSeq) -> command(type, commandSeq) }

    private fun paymentAfter(change: SagaChange): Payment? {
        val changesPayment = change.paymentLink != null || change.paymentExpiresAt != null || change.paymentId != null
        if (!changesPayment) return payment
        val current = payment ?: Payment()
        return current.copy(
            link = change.paymentLink ?: current.link,
            expiresAt = change.paymentExpiresAt ?: current.expiresAt,
            paymentId = change.paymentId ?: current.paymentId,
        )
    }

    private fun command(
        type: SagaCommandTypeEnum,
        commandSeq: Long,
    ): SagaCommand =
        when (type) {
            SagaCommandTypeEnum.RESERVE_STOCK -> {
                ReserveStockCommand(transactionId, orderId, commandSeq, repairs)
            }

            SagaCommandTypeEnum.GENERATE_BUDGET -> {
                GenerateBudgetCommand(transactionId, orderId, commandSeq, repairs, reservedMaterials)
            }

            SagaCommandTypeEnum.CONSUME_STOCK -> {
                ConsumeStockCommand(transactionId, orderId, commandSeq, reservedMaterials)
            }

            SagaCommandTypeEnum.RESTORE_STOCK -> {
                RestoreStockCommand(transactionId, orderId, commandSeq, reservedMaterials)
            }

            SagaCommandTypeEnum.MARK_STOCK_PENDING -> {
                MarkStockPendingCommand(transactionId, orderId, commandSeq)
            }

            SagaCommandTypeEnum.CLEAR_STOCK_PENDING -> {
                ClearStockPendingCommand(transactionId, orderId, commandSeq)
            }

            SagaCommandTypeEnum.RELEASE_ORDER -> {
                ReleaseOrderCommand(transactionId, orderId, commandSeq)
            }

            SagaCommandTypeEnum.CANCEL_ORDER -> {
                CancelOrderCommand(transactionId, orderId, commandSeq)
            }

            SagaCommandTypeEnum.SEND_PAYMENT_LINK -> {
                SendPaymentLinkCommand(transactionId, orderId, commandSeq, requirePaymentLink())
            }

            SagaCommandTypeEnum.SEND_APPROVAL -> {
                val currentBudget = requireBudget()
                SendApprovalCommand(transactionId, orderId, commandSeq, currentBudget.budgetId, currentBudget.orderCost)
            }

            SagaCommandTypeEnum.GENERATE_PAYMENT_LINK -> {
                val currentBudget = requireBudget()
                GeneratePaymentLinkCommand(transactionId, orderId, commandSeq, currentBudget.budgetId, currentBudget.orderCost)
            }
        }

    private fun requireBudget(): Budget = budget ?: throw SagaInconsistentStateException(transactionId, "orcamento ausente")

    private fun requirePaymentLink(): String =
        payment?.link ?: throw SagaInconsistentStateException(transactionId, "link de pagamento ausente")

    companion object {
        /** Cria a saga a partir do `diagnosisCompleted` (`insertOne` com `seq = 1`) e emite o `reserveStock`. */
        fun start(
            transactionId: UUID,
            event: DiagnosisCompleted,
            now: Instant,
        ): SagaAdvance {
            val transition = SagaStateMachine.creation
            val saga =
                Saga(
                    transactionId = transactionId,
                    orderId = event.orderId,
                    status = transition.nextStatus,
                    currentStep = transition.nextStep,
                    seq = transition.seqIncrement.toLong(),
                    repairs = event.repairs,
                    createdAt = now,
                    updatedAt = now,
                )
            return SagaAdvance(transition = transition, stepBefore = null, saga = saga, commands = saga.commandsFor(transition))
        }
    }
}
