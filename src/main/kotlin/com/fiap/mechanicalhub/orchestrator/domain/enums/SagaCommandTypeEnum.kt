package com.fiap.mechanicalhub.orchestrator.domain.enums

/**
 * Comandos publicados pelo orquestrador (spec secao 3, "Publica"). O nome da
 * constante e o `STEP` da `idempotencyKey = {transactionId}:{STEP}` e
 * `messageType` e o `type` do envelope.
 */
enum class SagaCommandTypeEnum(
    val messageType: String,
    val destination: CommandDestinationEnum,
) {
    RESERVE_STOCK("reserveStock", CommandDestinationEnum.STOCK),
    GENERATE_BUDGET("generateBudget", CommandDestinationEnum.BILLING),
    GENERATE_PAYMENT_LINK("generatePaymentLink", CommandDestinationEnum.BILLING),
    CONSUME_STOCK("consumeStock", CommandDestinationEnum.STOCK),
    RESTORE_STOCK("restoreStock", CommandDestinationEnum.STOCK),
    SEND_APPROVAL("sendApproval", CommandDestinationEnum.ORDERS),
    MARK_STOCK_PENDING("markStockPending", CommandDestinationEnum.ORDERS),
    CLEAR_STOCK_PENDING("clearStockPending", CommandDestinationEnum.ORDERS),
    SEND_PAYMENT_LINK("sendPaymentLink", CommandDestinationEnum.ORDERS),
    RELEASE_ORDER("releaseOrder", CommandDestinationEnum.ORDERS),
    CANCEL_ORDER("cancelOrder", CommandDestinationEnum.ORDERS),
    ;

    val step: String get() = name
}
