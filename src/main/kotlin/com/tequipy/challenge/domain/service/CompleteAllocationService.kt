package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.command.CompleteAllocationCommand
import com.tequipy.challenge.domain.model.AllocationCompletion
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
    override fun completeAllocations(commands: List<CompleteAllocationCommand>) {
        if (commands.isEmpty()) return

        val completions = commands.map { command ->
            AllocationCompletion(
                allocationId = command.allocationId,
                state = if (command.success) AllocationState.ALLOCATED else AllocationState.FAILED,
                allocatedEquipmentIds = if (command.success) command.allocatedEquipmentIds else emptyList()
            )
        }

        val appliedById = allocationRepository.completePendingBatch(completions).associateBy { it.id }

        commands.forEach { command ->
            val applied = appliedById[command.allocationId]
            if (applied == null) {
                logger.info {
                    "Ignoring allocation completed command for id=${command.allocationId} because allocation is missing or no longer pending"
                }
                return@forEach
            }

            logger.info { "Applied allocation completed command: id=${command.allocationId}, state=${applied.state}" }
        }
    }
}

