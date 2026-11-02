package com.fiap.mechanicalhub.orchestrator.application.usecases

import com.fiap.mechanicalhub.orchestrator.application.ports.CommandPublisher
import com.fiap.mechanicalhub.orchestrator.application.ports.OutboxMessage
import com.fiap.mechanicalhub.orchestrator.application.ports.OutboxRelayStore
import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaCommandTypeEnum
import com.fiap.mechanicalhub.orchestrator.logging.FlowFields
import com.fiap.mechanicalhub.orchestrator.logging.FlowLog
import com.fiap.mechanicalhub.orchestrator.logging.LogLevel
import com.fiap.mechanicalhub.orchestrator.logging.Stage
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Relay da outbox (TD_finishedOS §0.2): publica o lote de `PENDING` mais antigos.
 * Por `orderId` a ordem e crescente em `seq` e um `seq` so sai depois de o
 * anterior ter sido enviado. Um erro interrompe o lote inteiro, registrando
 * `attempts` e `lastError`; o mesmo comando e retentado no proximo ciclo.
 */
@Service
class RelayOutboxUseCase(
    private val store: OutboxRelayStore,
    private val publisher: CommandPublisher,
    private val clock: Clock,
) {
    /** Retorna quantos comandos foram enviados. */
    fun relayBatch(batchSize: Int = BATCH_SIZE): Int {
        val ordered =
            store
                .findPending(batchSize)
                .sortedWith(compareBy(OutboxMessage::createdAt, OutboxMessage::seq))
        var sent = 0
        for (message in ordered) {
            MDC.put("orderId", message.orderId)
            MDC.put("eventId", message.eventId)
            val command = SagaCommandTypeEnum.entries.firstOrNull { it.messageType == message.eventType }
            try {
                val destination = command?.destination ?: error("Tipo de comando sem fila de destino: ${message.eventType}")
                publisher.publish(destination, message)
                store.markSent(message.eventId, clock.instant())
                logPublication(message, command, "Comando ${message.eventType} publicado na fila do ${destination.name.lowercase()}", null)
                sent++
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logPublication(message, command, "Falha ao publicar comando ${message.eventType}, nova tentativa no proximo ciclo", e)
                store.markFailed(message.eventId, e.toString().take(MAX_ERROR_LENGTH))
                break
            } finally {
                MDC.remove("orderId")
                MDC.remove("eventId")
            }
        }
        return sent
    }

    /** Publicacao pelo relay (logging-standard §4): `status` do comando, para o filtro da etapa mostrar o envio. */
    private fun logPublication(
        message: OutboxMessage,
        command: SagaCommandTypeEnum?,
        msg: String,
        failure: Exception?,
    ) {
        val fields =
            FlowFields(
                orderId = message.orderId,
                status = command?.step,
                msg = msg,
                stage = if (failure != null) Stage.FAIL else null,
                context = listOf("eventType" to message.eventType, "seq" to message.seq, "relayAttempts" to message.attempts),
                error = failure,
            )
        FlowLog.write(log, if (failure != null) LogLevel.WARN else LogLevel.INFO, fields)
    }

    companion object {
        const val BATCH_SIZE = 100
        private const val MAX_ERROR_LENGTH = 1_000
        private val log = LoggerFactory.getLogger(RelayOutboxUseCase::class.java)
    }
}
