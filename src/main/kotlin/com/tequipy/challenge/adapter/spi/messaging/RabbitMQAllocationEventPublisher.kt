package com.tequipy.challenge.adapter.spi.messaging

import com.tequipy.challenge.adapter.api.messaging.events.AllocationCreated
import com.tequipy.challenge.adapter.api.messaging.events.EquipmentAllocated
import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.model.AllocationEntity
import com.tequipy.challenge.domain.model.AllocationProcessedResult
import com.tequipy.challenge.domain.port.spi.AllocationEventPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.util.UUID

@Component
class RabbitMQAllocationEventPublisher(
    private val rabbitTemplate: RabbitTemplate
) : AllocationEventPublisher {

    private val logger = KotlinLogging.logger {}

    override fun publishAllocationCreated(allocation: AllocationEntity) = afterCommit {
        logger.debug { "Publishing allocation created: id=${allocation.id}" }
        val message = AllocationCreated(
            id = allocation.id,
            policy = allocation.policy.map {
                AllocationCreated.PolicyRequirementMessage(it.type, it.quantity, it.minimumConditionScore, it.preferredBrand)
            },
            timestamp = Instant.now()
        )
        rabbitTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_QUEUE, message)
    }

    override fun publishAllocationProcessed(allocationId: UUID, success: Boolean, allocatedEquipmentIds: List<UUID>) {
        publishAllocationProcessedBatch(listOf(AllocationProcessedResult(allocationId, success, allocatedEquipmentIds)))
    }

    override fun publishAllocationProcessedBatch(results: List<AllocationProcessedResult>) {
        if (results.isEmpty()) return
        afterCommit {
            logger.debug { "Publishing allocation processed batch: ${results.size} result(s)" }
            val message = EquipmentAllocated(
                results = results.map {
                    EquipmentAllocated.AllocationProcessedResultMessage(it.allocationId, it.success, it.allocatedEquipmentIds)
                },
                timestamp = Instant.now()
            )
            rabbitTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_RESULT_QUEUE, message)
        }
    }

    private fun afterCommit(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }
}
