package com.tequipy.challenge.adapter.out.messaging

import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.port.out.AllocationEventPublisher
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

@Component
class RabbitMQAllocationEventPublisher(
    private val rabbitTemplate: RabbitTemplate
) : AllocationEventPublisher {

    override fun publishAllocationCreated(allocationId: UUID) {
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                rabbitTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_QUEUE, allocationId.toString())
            }
        })
    }
}
