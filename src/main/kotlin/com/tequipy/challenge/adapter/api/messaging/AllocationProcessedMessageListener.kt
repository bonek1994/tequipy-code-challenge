package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.amqp.rabbit.annotation.RabbitListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class AllocationProcessedMessageListener(
    private val allocationRepository: AllocationRepository
) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    @RabbitListener(
        queues = [RabbitMQConfig.ALLOCATION_RESULT_QUEUE],
        containerFactory = "allocationResultListenerContainerFactory"
    )
    fun onAllocationProcessed(message: AllocationProcessedMessage) {
        val targetState = if (message.success) AllocationState.ALLOCATED else AllocationState.FAILED
        val applied = allocationRepository.completePending(
            id = message.id,
            state = targetState,
            allocatedEquipmentIds = if (message.success) message.allocatedEquipmentIds else emptyList()
        )

        if (applied == null) {
            logger.info {
                "Ignoring allocation processed message for id=${message.id} because allocation is missing or no longer pending"
            }
            return
        }

        logger.info { "Applied allocation processed message: id=${message.id}, state=${applied.state}" }
    }
}

