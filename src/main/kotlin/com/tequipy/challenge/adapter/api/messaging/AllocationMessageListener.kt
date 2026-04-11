package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.service.AllocationProcessor
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AllocationMessageListener(
    private val allocationProcessor: AllocationProcessor
) {
    @RabbitListener(queues = [RabbitMQConfig.ALLOCATION_QUEUE])
    fun onAllocationCreated(allocationId: String) {
        allocationProcessor.processAllocation(UUID.fromString(allocationId))
    }
}
