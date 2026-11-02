package com.fiap.mechanicalhub.orchestrator.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fiap.mechanicalhub.orchestrator.domain.exceptions.SagaInconsistentStateException
import com.fiap.mechanicalhub.orchestrator.fixtures.SagaFixtures
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class FlowLogTest {
    private val logger = LoggerFactory.getLogger("flow-log-test") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        logger.level = Level.DEBUG
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        FlowLog.current()?.end("cleanup")
        MDC.clear()
    }

    private fun messages() = appender.list.map { it.formattedMessage }

    @Test
    fun `given a camel case message type when computing the status then it becomes upper snake case`() {
        assertThat(FlowLog.statusOf("generatePaymentLink")).isEqualTo("GENERATE_PAYMENT_LINK")
        assertThat(FlowLog.statusOf("stock2Reserved")).isEqualTo("STOCK2_RESERVED")
    }

    @Test
    fun `given an open step when ending then it logs start and end with context and releases the thread`() {
        val step = FlowLog.begin(logger, "order-1", "DIAGNOSIS_COMPLETED", attempt = 2, clock = fixedClock())

        step.start("Recebido", "eventId" to "e1")
        FlowLog.detail("materials" to listOf("a", "b"), "total" to BigDecimal("10.50"), "empty" to emptyList<String>())
        FlowLog.endMessage("Saga avancou")
        step.end("padrao")

        assertThat(messages()).containsExactly(
            "orderId=order-1 status=DIAGNOSIS_COMPLETED stage=START attempt=2 eventId=e1 msg=\"Recebido\"",
            "orderId=order-1 status=DIAGNOSIS_COMPLETED stage=END attempt=2 durationMs=0 eventId=e1 " +
                "materials=a,b total=10.50 msg=\"Saga avancou\"",
        )
        assertThat(FlowLog.current()).isNull()
    }

    @Test
    fun `given no custom end message when ending then the default message is used`() {
        val step = FlowLog.begin(logger, "o", null, clock = fixedClock())

        step.end("padrao")

        assertThat(messages().single()).isEqualTo("orderId=o stage=END durationMs=0 msg=\"padrao\"")
    }

    @Test
    fun `given a retryable failure when warning then it logs WARN without a stack trace`() {
        val step = FlowLog.begin(logger, "o", "S", clock = fixedClock())

        step.warnFail("Tentar de novo", IllegalStateException("boom"))

        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.WARN)
        assertThat(event.throwableProxy).isNull()
        assertThat(event.formattedMessage).contains("stage=FAIL", "ex=IllegalStateException", "exMsg=\"Falha em IllegalStateException\"")
    }

    @Test
    fun `given a permanent failure when erroring then it logs ERROR with the cause attached`() {
        val step = FlowLog.begin(logger, "o", "S", clock = fixedClock())

        step.errorFail("DLQ", IllegalArgumentException("x"))

        val event = appender.list.single()
        assertThat(event.level).isEqualTo(Level.ERROR)
        assertThat(event.throwableProxy.className).isEqualTo(IllegalArgumentException::class.java.name)
    }

    @Test
    fun `given an exception from the own service in the cause chain when formatting then its message is used`() {
        val own = SagaInconsistentStateException(SagaFixtures.TRANSACTION_ID, "estado   invalido \"x\"")

        FlowLog.write(logger, LogLevel.ERROR, FlowFields("o", "S", "falha", error = RuntimeException("wrapper", own)))

        assertThat(messages().single()).contains("em estado inconsistente: estado invalido 'x'")
    }

    @Test
    fun `given each level when writing then the matching slf4j level is used`() {
        LogLevel.entries.forEach { FlowLog.write(logger, it, FlowFields("o", null, "m")) }

        assertThat(appender.list.map { it.level }).containsExactly(Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR)
    }

    @Test
    fun `given a very long message with whitespace and quotes when writing then it is sanitized and truncated`() {
        FlowLog.write(logger, LogLevel.INFO, FlowFields("o", null, "a \"b\"\n" + "x".repeat(400)))

        val msg = messages().single().substringAfter("msg=\"").removeSuffix("\"")
        assertThat(msg).startsWith("a 'b' x").hasSize(300)
    }

    @Test
    fun `given a step with an order when noting then the line carries order and status`() {
        FlowLog.begin(logger, "o1", "ST", clock = fixedClock())

        FlowLog.note(logger, LogLevel.WARN, "alerta", "k" to "v")

        assertThat(messages().single()).isEqualTo("orderId=o1 status=ST k=v msg=\"alerta\"")
    }

    @Test
    fun `given no step but an order in the MDC when noting then the MDC order is used`() {
        MDC.put(FlowLog.ORDER_ID_KEY, "mdc-order")

        FlowLog.note(logger, LogLevel.INFO, "avulso")

        assertThat(messages().single()).isEqualTo("orderId=mdc-order msg=\"avulso\"")
    }

    @Test
    fun `given a line about another order when writing then the MDC is swapped only during the write`() {
        MDC.put(FlowLog.ORDER_ID_KEY, "current")

        FlowLog.write(logger, LogLevel.INFO, FlowFields("other", null, "m"))

        assertThat(appender.list.single().mdcPropertyMap[FlowLog.ORDER_ID_KEY]).isEqualTo("other")
        assertThat(MDC.get(FlowLog.ORDER_ID_KEY)).isEqualTo("current")
    }

    @Test
    fun `given no previous MDC order when writing another order then the MDC is cleaned afterwards`() {
        FlowLog.write(logger, LogLevel.INFO, FlowFields("other", null, "m"))

        assertThat(MDC.get(FlowLog.ORDER_ID_KEY)).isNull()
    }

    @Test
    fun `given an order discovered after start when assigning then later lines and the MDC use it`() {
        val step = FlowLog.begin(logger, null, "WEBHOOK", clock = fixedClock())

        FlowLog.assignOrder(null)
        assertThat(step.orderId).isNull()
        FlowLog.assignOrder("late")
        step.end("fim")

        assertThat(step.orderId).isEqualTo("late")
        assertThat(messages().single()).startsWith("orderId=late status=WEBHOOK stage=END")
    }

    @Test
    fun `given no open step when using the helpers then they do nothing`() {
        FlowLog.detail("k" to "v")
        FlowLog.endMessage("m")
        FlowLog.assignOrder("x")

        assertThat(FlowLog.current()).isNull()
        assertThat(appender.list).isEmpty()
    }

    private fun fixedClock() = Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC)
}
