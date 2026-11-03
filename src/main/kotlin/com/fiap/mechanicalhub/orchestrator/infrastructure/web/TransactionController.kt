package com.fiap.mechanicalhub.orchestrator.infrastructure.web

import com.fiap.mechanicalhub.orchestrator.application.ports.SagaRepository
import com.fiap.mechanicalhub.orchestrator.application.ports.TransactionHistoryQueryPort
import com.fiap.mechanicalhub.orchestrator.application.usecases.GetTransactionUseCase
import com.fiap.mechanicalhub.orchestrator.domain.model.Saga
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.util.UUID

/** Consulta operacional interna (depuracao e demonstracao); nao e endpoint publico. */
@RestController
class TransactionController(
    sagaRepository: SagaRepository,
    historyQuery: TransactionHistoryQueryPort,
) {
    private val useCase = GetTransactionUseCase(sagaRepository, historyQuery)

    @GetMapping("/transaction/{orderId}")
    fun get(
        @PathVariable orderId: UUID,
    ): ResponseEntity<TransactionResponse> =
        useCase.execute(orderId)?.let { view ->
            ResponseEntity.ok(
                TransactionResponse(
                    transaction = view.saga.toResponse(),
                    history =
                        view.history.map {
                            HistoryResponse(
                                it.seq,
                                it.messageType,
                                it.messageKey,
                                it.stepBefore?.name,
                                it.stepAfter?.name,
                                it.outcome.name,
                                it.receivedAt,
                            )
                        },
                ),
            )
        } ?: ResponseEntity.notFound().build()

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidOrderId(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
}

private fun Saga.toResponse() =
    SagaResponse(
        transactionId = transactionId,
        orderId = orderId,
        status = status.name,
        currentStep = currentStep?.name,
        seq = seq,
        repairs = repairs.map { it.repairId },
        reservedMaterials = reservedMaterials.map { ReservedMaterialResponse(it.materialId, it.quantity) },
        budget = budget?.let { BudgetResponse(it.budgetId, it.orderCost) },
        payment = payment?.let { PaymentResponse(it.link, it.expiresAt, it.paymentId) },
        createdAt = createdAt,
        updatedAt = updatedAt,
        finishedAt = finishedAt,
    )
