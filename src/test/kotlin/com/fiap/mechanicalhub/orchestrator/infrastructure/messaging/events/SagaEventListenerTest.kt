package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.events

import io.awspring.cloud.sqs.listener.SqsHeaders
import io.awspring.cloud.sqs.listener.Visibility
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder

class SagaEventListenerTest {
    private val processor = mockk<SagaEventMessageProcessor>()
    private val deadLetterPublisher = mockk<EventDeadLetterPublisher>(relaxed = true)
    private val acknowledgement = mockk<Acknowledgement>(relaxed = true)
    private val visibility = mockk<Visibility>(relaxed = true)
    private val listener = SagaEventListener(processor, deadLetterPublisher)

    private fun message(receiveCount: Any? = null): Message<String> {
        val builder = MessageBuilder.withPayload("""{"type":"stockReserved"}""")
        builder.setHeader(SqsHeaders.MessageSystemAttributes.SQS_MESSAGE_GROUP_ID_HEADER, "order-1")
        receiveCount?.let { builder.setHeader(SqsHeaders.MessageSystemAttributes.SQS_APPROXIMATE_RECEIVE_COUNT, it) }
        return builder.build()
    }

    @Test
    fun `given an acknowledge decision when a message arrives then it is acknowledged`() {
        every { processor.process(any(), 3) } returns EventDecision.Acknowledge

        listener.onMessage(message("3"), acknowledgement, visibility)

        verify { acknowledgement.acknowledge() }
        verify(exactly = 0) { visibility.changeTo(any()) }
        verify(exactly = 0) { deadLetterPublisher.publish(any(), any()) }
    }

    @Test
    fun `given a missing or invalid receive count when a message arrives then the first attempt is assumed`() {
        every { processor.process(any(), 1) } returns EventDecision.Acknowledge

        listener.onMessage(message(), acknowledgement, visibility)
        listener.onMessage(message("not-a-number"), acknowledgement, visibility)

        verify(exactly = 2) { processor.process(any(), 1) }
    }

    @Test
    fun `given a retry decision when a message arrives then the visibility is changed and it is not acknowledged`() {
        every { processor.process(any(), 2) } returns EventDecision.Retry(30, IllegalStateException("db down"))

        listener.onMessage(message(2), acknowledgement, visibility)

        verify { visibility.changeTo(30) }
        verify(exactly = 0) { acknowledgement.acknowledge() }
    }

    @Test
    fun `given a dead letter decision when a message arrives then it is published to the DLQ and acknowledged`() {
        val error = IllegalArgumentException("invalid payload")
        every { processor.process(any(), 1) } returns EventDecision.DeadLetter(error)
        every { processor.statusOf(any()) } returns "STOCK_RESERVED"
        every { deadLetterPublisher.publish(any(), error) } just Runs

        val message = message()
        listener.onMessage(message, acknowledgement, visibility)

        verify { deadLetterPublisher.publish(message, error) }
        verify { acknowledgement.acknowledge() }
    }
}
