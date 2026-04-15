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

        val completions = commands.map { it.toCompletion() }
        val appliedById = allocationRepository.completePendingBatch(completions).associateBy { it.id }

        commands.forEach { cmd ->
            if (cmd.allocationId in appliedById) {
                logger.info { "Applied allocation completed: id=${cmd.allocationId}, state=${appliedById[cmd.allocationId]?.state}" }
            } else {
                logger.info { "Skipped allocation ${cmd.allocationId}: missing or no longer pending" }
            }
        }
    }

    private fun CompleteAllocationCommand.toCompletion() = AllocationCompletion(
        allocationId = allocationId,
        state = if (success) AllocationState.ALLOCATED else AllocationState.FAILED,
        allocatedEquipmentIds = if (success) allocatedEquipmentIds else emptyList()
    )
}
