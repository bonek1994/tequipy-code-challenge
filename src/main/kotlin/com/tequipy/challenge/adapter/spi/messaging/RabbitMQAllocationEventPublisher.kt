package com.tequipy.challenge.adapter.spi.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.tequipy.challenge.adapter.api.messaging.AllocationMessage
import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.port.out.AllocationEventPublisher
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
class RabbitMQAllocationEventPublisher(
    private val rabbitTemplate: RabbitTemplate,
    private val objectMapper: ObjectMapper
) : AllocationEventPublisher {

    override fun publishAllocationCreated(allocation: AllocationRequest) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                val message = AllocationMessage(
                    id = allocation.id,
                    policy = allocation.policy.map { req ->
                        AllocationMessage.PolicyRequirementMessage(
                            type = req.type,
                            quantity = req.quantity,
                            minimumConditionScore = req.minimumConditionScore,
                            preferredBrand = req.preferredBrand
                        )
                    }
                )
                rabbitTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_QUEUE, objectMapper.writeValueAsString(message))
            }
        })
    }
}
