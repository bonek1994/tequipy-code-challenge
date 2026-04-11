package com.tequipy.challenge.adapter.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.service.AllocationProcessor
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

@Component
class AllocationMessageListener(
    private val allocationProcessor: AllocationProcessor,
    private val objectMapper: ObjectMapper
) {
    @RabbitListener(queues = [RabbitMQConfig.ALLOCATION_QUEUE])
    fun onAllocationCreated(message: String) {
        val allocationMessage = objectMapper.readValue(message, AllocationMessage::class.java)
        val allocation = AllocationRequest(
            id = allocationMessage.id,
            state = AllocationState.PENDING,
            policy = allocationMessage.policy.map { req ->
                EquipmentPolicyRequirement(
                    type = req.type,
                    quantity = req.quantity,
                    minimumConditionScore = req.minimumConditionScore,
                    preferredBrand = req.preferredBrand
                )
            },
            allocatedEquipmentIds = emptyList()
        )
        allocationProcessor.processAllocation(allocation)
    }
}
