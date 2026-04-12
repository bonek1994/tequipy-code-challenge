package com.tequipy.challenge.adapter.spi.messaging

import com.tequipy.challenge.adapter.api.messaging.AllocationProcessedMessage
import com.tequipy.challenge.adapter.api.messaging.AllocationRequestedMessage
import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.port.spi.AllocationEventPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

@Component
class RabbitMQAllocationEventPublisher(
    private val rabbitTemplate: RabbitTemplate
) : AllocationEventPublisher {

    private val logger = KotlinLogging.logger {}

    override fun publishAllocationCreated(allocation: AllocationRequest) {
        afterCommit {
            logger.debug { "Publishing allocation created event: id=${allocation.id}" }
            val message = AllocationRequestedMessage(
                id = allocation.id,
                policy = allocation.policy.map { req ->
                    AllocationRequestedMessage.PolicyRequirementMessage(
                        type = req.type,
                        quantity = req.quantity,
                        minimumConditionScore = req.minimumConditionScore,
                        preferredBrand = req.preferredBrand
                    )
                }
            )
            rabbitTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_QUEUE, message)
            logger.debug { "Allocation created event published: id=${allocation.id}" }
        }
    }

    override fun publishAllocationProcessed(allocationId: UUID, success: Boolean, allocatedEquipmentIds: List<UUID>) {
        afterCommit {
            logger.debug { "Publishing allocation processed event: id=$allocationId, success=$success" }
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.ALLOCATION_RESULT_QUEUE,
                AllocationProcessedMessage(
                    id = allocationId,
                    success = success,
                    allocatedEquipmentIds = allocatedEquipmentIds
                )
            )
            logger.debug { "Allocation processed event published: id=$allocationId, success=$success" }
        }
    }

    private fun afterCommit(callback: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            callback()
            return
        }

        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                callback()
            }
        })
    }
}
