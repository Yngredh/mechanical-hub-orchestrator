package com.fiap.mechanicalhub.orchestrator.infrastructure.persistence.documents

import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaStatusEnum
import com.fiap.mechanicalhub.orchestrator.domain.enums.SagaStepEnum
import com.fiap.mechanicalhub.orchestrator.infrastructure.persistence.MongoCollections
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * Modelos de persistencia (data_model.md §5). Os nomes dos campos sao os dos
 * indices criados pelo `MongoIndexInitializer`; UUIDs gravados como string.
 */
@Document(MongoCollections.TRANSACTION_REGISTER)
data class TransactionRegisterDocument(
    @Id val id: String,
    val orderId: String,
    val status: SagaStatusEnum,
    val currentStep: SagaStepEnum?,
    val seq: Long,
    val repairs: List<RepairDocument>,
    val reservedMaterials: List<ReservedMaterialDocument>,
    val budget: BudgetDocument?,
    val payment: PaymentDocument?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finishedAt: Instant?,
)
