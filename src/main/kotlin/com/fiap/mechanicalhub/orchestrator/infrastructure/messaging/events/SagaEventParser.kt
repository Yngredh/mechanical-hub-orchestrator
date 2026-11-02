package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.events

import com.fiap.mechanicalhub.orchestrator.application.usecases.IncomingSagaEvent
import com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts.EventMessage
import com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.contracts.toDomain
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper

/** Converte o corpo JSON (envelope + payload no mesmo nivel) no evento de dominio, escolhendo o contrato pelo `type`. */
@Component
class SagaEventParser(
    private val jsonMapper: JsonMapper,
) {
    private val reader = jsonMapper.readerFor(EventMessage::class.java).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    fun parse(body: String): IncomingSagaEvent {
        val message =
            try {
                reader.readValue<EventMessage>(body)
            } catch (exception: JacksonException) {
                throw InvalidMessageException("Mensagem fora do contrato: ${exception.originalMessage}", exception)
            }
        val event =
            try {
                message.toDomain()
            } catch (exception: IllegalArgumentException) {
                throw InvalidMessageException("Valor fora do contrato em ${message.type}: ${exception.message}", exception)
            }
        return IncomingSagaEvent(message.eventId, event)
    }

    /** Campo texto do envelope sem validar o contrato (null se ausente ou ilegivel); usado nos logs. */
    fun fieldOrNull(
        body: String,
        field: String,
    ): String? =
        runCatching { jsonMapper.readTree(body).get(field)?.takeIf { it.isString }?.asString() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
}
