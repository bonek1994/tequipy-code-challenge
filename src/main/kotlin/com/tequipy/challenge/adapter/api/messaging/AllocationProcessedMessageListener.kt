package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.adapter.api.messaging.events.EquipmentAllocated
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
    fun onAllocationProcessed(message: EquipmentAllocated) {
        logger.info { "Received allocation processed batch message with ${message.results.size} result(s)" }
        completeAllocationUseCase.completeAllocations(
            message.results.map { result ->
                CompleteAllocationCommand(
                    allocationId = result.id,
                    success = result.success,
                    allocatedEquipmentIds = result.allocatedEquipmentIds
                )
            }
        )
    }
}

