package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.command.CompleteAllocationCommand
import com.tequipy.challenge.domain.port.api.CompleteAllocationUseCase
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component

@Component
class AllocationProcessedMessageListener(
    private val completeAllocationUseCase: CompleteAllocationUseCase
) {
    private val logger = KotlinLogging.logger {}

    @RabbitListener(
        queues = [RabbitMQConfig.ALLOCATION_RESULT_QUEUE],
        containerFactory = "allocationResultListenerContainerFactory"
    )
    fun onAllocationProcessed(message: AllocationProcessedMessage) {
        logger.info { "Received allocation processed message: id=${message.id}" }
        completeAllocationUseCase.completeAllocation(
            CompleteAllocationCommand(
                allocationId = message.id,
                success = message.success,
                allocatedEquipmentIds = message.allocatedEquipmentIds
            )
        )
    }
}

