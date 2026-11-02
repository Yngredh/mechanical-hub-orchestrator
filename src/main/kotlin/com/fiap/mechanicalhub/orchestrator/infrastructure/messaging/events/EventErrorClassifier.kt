package com.fiap.mechanicalhub.orchestrator.infrastructure.messaging.events

import com.fiap.mechanicalhub.orchestrator.application.ports.DuplicateMessageException
import com.fiap.mechanicalhub.orchestrator.application.usecases.UnsupportedSagaEventException
import com.fiap.mechanicalhub.orchestrator.domain.exceptions.SagaDomainException
import com.mongodb.MongoException
import com.mongodb.MongoExecutionTimeoutException
import com.mongodb.MongoNodeIsRecoveringException
import com.mongodb.MongoNotPrimaryException
import com.mongodb.MongoSocketException
import com.mongodb.MongoTimeoutException
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.NonTransientDataAccessException
import org.springframework.dao.RecoverableDataAccessException
import org.springframework.dao.TransientDataAccessException

/**
 * Classificacao de failure_handling.md §1 para o consumidor do orquestrador.
 * Transitorio: `TransientTransactionError`, `UnknownTransactionCommitResult`,
 * `WriteConflict`, `NotWritablePrimary` e demais trocas de primario, timeouts e
 * falhas de conexao. Permanente: fora do contrato, evento sem handler, estado
 * de saga inconsistente e erro de dado nao retentavel. Qualquer outra falha e
 * tratada como transitoria: se nao se resolver, a redrive policy leva a DLQ.
 */
object EventErrorClassifier {
    fun classify(error: Throwable): EventErrorClass {
        val chain = generateSequence(error) { it.cause }.toList()
        return when {
            chain.any { it is DuplicateMessageException } -> EventErrorClass.DUPLICATE
            chain.any(::isTransient) -> EventErrorClass.TRANSIENT
            chain.any(::isPermanent) -> EventErrorClass.PERMANENT
            else -> EventErrorClass.TRANSIENT
        }
    }

    private fun isTransient(error: Throwable): Boolean =
        when (error) {
            is MongoException -> error.isTransientMongoFailure()
            is TransientDataAccessException, is RecoverableDataAccessException, is DataAccessResourceFailureException -> true
            else -> false
        }

    private fun MongoException.isTransientMongoFailure(): Boolean =
        hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) ||
            hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL) ||
            code in TRANSIENT_MONGO_CODES ||
            this is MongoNotPrimaryException ||
            this is MongoNodeIsRecoveringException ||
            this is MongoSocketException ||
            this is MongoTimeoutException ||
            this is MongoExecutionTimeoutException

    private fun isPermanent(error: Throwable): Boolean =
        error is InvalidMessageException ||
            error is UnsupportedSagaEventException ||
            error is SagaDomainException ||
            error is NonTransientDataAccessException

    /**
     * WriteConflict (112), NotWritablePrimary (10107), NotPrimaryNoSecondaryOk (13435),
     * InterruptedDueToReplStateChange (11602), PrimarySteppedDown (189),
     * ShutdownInProgress (91), MaxTimeMSExpired (50).
     */
    private val TRANSIENT_MONGO_CODES = setOf(112, 10107, 13435, 11602, 189, 91, 50)
}
