package com.tequipy.challenge.domain.service

import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.event.AllocationCreatedEvent
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class AllocationEventHandler(
    private val rabbitTemplate: RabbitTemplate
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAllocationCreated(event: AllocationCreatedEvent) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_QUEUE, event.allocationId.toString())
    }
}





