package com.fiap.mechanicalhub.orchestrator.domain.model

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaEventTypeEnum
import java.util.UUID

data class OrderFinished(
    override val transactionId: UUID,
    override val orderId: UUID,
) : SagaResponseEvent {
    override val type = SagaEventTypeEnum.ORDER_FINISHED
}
