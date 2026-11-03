package com.fiap.mechanicalhub.orchestrator.infrastructure.web

import com.fiap.mechanicalhub.orchestrator.application.ports.SagaRepository
import com.fiap.mechanicalhub.orchestrator.application.ports.TransactionHistoryEntry
import com.fiap.mechanicalhub.orchestrator.application.ports.TransactionHistoryQueryPort
import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaStatusEnum
import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaStepEnum
import com.fiap.mechanicalhub.orchestrator.domain.enums.TransactionOutcomeEnum
import com.fiap.mechanicalhub.orchestrator.domain.model.Payment
import com.fiap.mechanicalhub.orchestrator.fixtures.SagaFixtures
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

class TransactionControllerTest {
    private val sagaRepository = mockk<SagaRepository>()
    private val historyQuery = mockk<TransactionHistoryQueryPort>()
    private val controller = TransactionController(sagaRepository, historyQuery)

    @Test
    fun `given an existing saga when querying then it maps the saga and its history to the response`() {
        val payment = Payment(SagaFixtures.PAYMENT_LINK, SagaFixtures.PAYMENT_EXPIRES_AT, "pay-1")
        val saga =
            SagaFixtures.saga(
                status = SagaStatusEnum.STARTED,
                currentStep = SagaStepEnum.AWAITING_PAYMENT,
                payment = payment,
            )
        val entry =
            TransactionHistoryEntry(
                saga.transactionId,
                saga.orderId,
                2,
                "budgetGenerated",
                "e2",
                SagaStepEnum.GENERATE_BUDGET,
                SagaStepEnum.AWAITING_PAYMENT,
                TransactionOutcomeEnum.APPLIED,
                SagaFixtures.NOW,
            )
        every { sagaRepository.findByOrderId(saga.orderId) } returns saga
        every { historyQuery.findByOrderId(saga.orderId) } returns listOf(entry)

        val response = controller.get(saga.orderId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body.transaction.transactionId).isEqualTo(saga.transactionId)
        assertThat(body.transaction.orderId).isEqualTo(saga.orderId)
        assertThat(body.transaction.status).isEqualTo("STARTED")
        assertThat(body.transaction.currentStep).isEqualTo("AWAITING_PAYMENT")
        assertThat(body.transaction.repairs).isEqualTo(SagaFixtures.REPAIRS.map { it.repairId })
        assertThat(body.transaction.reservedMaterials.map { it.quantity }).containsExactly(4, 2)
        assertThat(body.transaction.budget).isEqualTo(BudgetResponse(SagaFixtures.BUDGET.budgetId, SagaFixtures.BUDGET.orderCost))
        assertThat(body.transaction.payment)
            .isEqualTo(PaymentResponse(SagaFixtures.PAYMENT_LINK, SagaFixtures.PAYMENT_EXPIRES_AT, "pay-1"))
        assertThat(body.history).containsExactly(
            HistoryResponse(2, "budgetGenerated", "e2", "GENERATE_BUDGET", "AWAITING_PAYMENT", "APPLIED", SagaFixtures.NOW),
        )
    }

    @Test
    fun `given a saga without optional parts when querying then the optional fields are null`() {
        val saga = SagaFixtures.saga(currentStep = null, budget = null, payment = null)
        every { sagaRepository.findByOrderId(saga.orderId) } returns saga
        every { historyQuery.findByOrderId(saga.orderId) } returns emptyList()

        val body = controller.get(saga.orderId).body!!

        assertThat(body.transaction.currentStep).isNull()
        assertThat(body.transaction.budget).isNull()
        assertThat(body.transaction.payment).isNull()
        assertThat(body.history).isEmpty()
    }

    @Test
    fun `given an unknown order when querying then it returns 404`() {
        every { sagaRepository.findByOrderId(SagaFixtures.ORDER_ID) } returns null

        assertThat(controller.get(SagaFixtures.ORDER_ID).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `given a malformed order id when handling the mismatch then it returns 400`() {
        assertThat(controller.invalidOrderId().statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
