package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.service.AllocationProcessor
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

@Component
class AllocationMessageListener(
    private val allocationProcessor: AllocationProcessor
) {
    @RabbitListener(queues = [RabbitMQConfig.ALLOCATION_QUEUE])
    fun onAllocationCreated(message: AllocationMessage) {
        val allocation = AllocationRequest(
            id = message.id,
            state = AllocationState.PENDING,
            policy = message.policy.map { req ->
                EquipmentPolicyRequirement(
                    type = req.type,
                    quantity = req.quantity,
                    minimumConditionScore = req.minimumConditionScore,
                    preferredBrand = req.preferredBrand
                )
            }
        )
        allocationProcessor.processAllocation(allocation)
    }
}
