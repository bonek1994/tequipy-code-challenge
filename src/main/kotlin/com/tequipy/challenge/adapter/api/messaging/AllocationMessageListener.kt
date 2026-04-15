package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.adapter.api.messaging.events.AllocationCreated
import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.command.ProcessAllocationCommand
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.service.BatchAllocationCollector
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

@Component
class AllocationMessageListener(
    private val batchAllocationCollector: BatchAllocationCollector
) {
    private val logger = KotlinLogging.logger {}

    @RabbitListener(queues = [RabbitMQConfig.ALLOCATION_QUEUE])
    fun onAllocationCreated(message: AllocationCreated) {
        logger.info { "Received allocation message: id=${message.id}" }
        batchAllocationCollector.submit(
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
