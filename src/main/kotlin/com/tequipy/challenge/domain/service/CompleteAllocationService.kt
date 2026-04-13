package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.command.CompleteAllocationCommand
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.port.api.CompleteAllocationUseCase
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CompleteAllocationService(
    private val allocationRepository: AllocationRepository
) : CompleteAllocationUseCase {
    private val logger = KotlinLogging.logger {}

    @Transactional
    override fun completeAllocation(command: CompleteAllocationCommand) {
        val targetState = if (command.success) AllocationState.ALLOCATED else AllocationState.FAILED
        val applied = allocationRepository.completePending(
            id = command.allocationId,
            state = targetState,
            allocatedEquipmentIds = if (command.success) command.allocatedEquipmentIds else emptyList()
        )

        if (applied == null) {
            logger.info {
                "Ignoring allocation completed command for id=${command.allocationId} because allocation is missing or no longer pending"
            }
            return
        }

        logger.info { "Applied allocation completed command: id=${command.allocationId}, state=${applied.state}" }
    }
}

