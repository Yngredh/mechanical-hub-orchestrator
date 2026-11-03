package com.fiap.mechanicalhub.orchestrator.application.ports

/** `transaction_history`: deduplicacao por `messageKey = eventId`; participa da transacao do caso de uso. */
interface TransactionHistoryPort {
    fun existsByMessageKey(messageKey: String): Boolean

    /** Lanca [DuplicateMessageException] se o `messageKey` ja existir. */
    fun append(entry: TransactionHistoryEntry)
}
