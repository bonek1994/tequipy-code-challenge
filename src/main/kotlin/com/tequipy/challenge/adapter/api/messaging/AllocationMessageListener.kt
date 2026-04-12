package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.port.api.ProcessAllocationCommand
import com.tequipy.challenge.domain.port.api.ProcessAllocationUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

@Component
class AllocationMessageListener(
    private val processAllocationUseCase: ProcessAllocationUseCase
) {
    private val logger = KotlinLogging.logger {}

    @RabbitListener(queues = [RabbitMQConfig.ALLOCATION_QUEUE])
    fun onAllocationCreated(message: AllocationRequestedMessage) {
        logger.info { "Received allocation message: id=${message.id}" }
        processAllocationUseCase.processAllocation(
            ProcessAllocationCommand(
                allocationId = message.id,
                policy = message.policy.map { requirement ->
                    EquipmentPolicyRequirement(
                        type = requirement.type,
                        quantity = requirement.quantity,
                        minimumConditionScore = requirement.minimumConditionScore,
                        preferredBrand = requirement.preferredBrand
                    )
                }
            )
        )
    }
}
