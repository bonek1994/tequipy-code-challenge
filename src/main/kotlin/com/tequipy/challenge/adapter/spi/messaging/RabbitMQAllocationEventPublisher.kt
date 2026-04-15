package com.tequipy.challenge.adapter.spi.messaging

import com.tequipy.challenge.adapter.api.messaging.events.EquipmentAllocated
import com.tequipy.challenge.adapter.api.messaging.events.AllocationCreated
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

    override fun publishAllocationCreated(allocation: AllocationEntity) {
        afterCommit {
            logger.debug { "Publishing allocation created event: id=${allocation.id}" }
            val message = AllocationCreated(
                id = allocation.id,
                policy = allocation.policy.map { req ->
                    AllocationCreated.PolicyRequirementMessage(
                        type = req.type,
                        quantity = req.quantity,
                        minimumConditionScore = req.minimumConditionScore,
                        preferredBrand = req.preferredBrand
                    )
                },
                timestamp = Instant.now()
            )
            rabbitTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_QUEUE, message)
            logger.debug { "Allocation created event published: id=${allocation.id}" }
        }
    }

    override fun publishAllocationProcessed(allocationId: UUID, success: Boolean, allocatedEquipmentIds: List<UUID>) {
        publishAllocationProcessedBatch(
            listOf(
                AllocationProcessedResult(
                    allocationId = allocationId,
                    success = success,
                    allocatedEquipmentIds = allocatedEquipmentIds
                )
            )
        )
    }

    override fun publishAllocationProcessedBatch(results: List<AllocationProcessedResult>) {
        if (results.isEmpty()) {
            return
        }

        afterCommit {
            logger.debug { "Publishing allocation processed batch event with ${results.size} result(s)" }
            rabbitTemplate.convertAndSend(
                RabbitMQConfig.ALLOCATION_RESULT_QUEUE,
                EquipmentAllocated(
                    results = results.map { result ->
                        EquipmentAllocated.AllocationProcessedResultMessage(
                            id = result.allocationId,
                            success = result.success,
                            allocatedEquipmentIds = result.allocatedEquipmentIds
                        )
                    },
                    timestamp = Instant.now()
                )
            )
            logger.debug { "Allocation processed batch event published with ${results.size} result(s)" }
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
