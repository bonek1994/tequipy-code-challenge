package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.AllocationLockContentionException
import com.tequipy.challenge.domain.model.AllocationProcessingState
import com.tequipy.challenge.domain.port.api.ProcessAllocationCommand
import com.tequipy.challenge.domain.port.api.ProcessAllocationUseCase
import com.tequipy.challenge.domain.port.spi.AllocationEventPublisher
import com.tequipy.challenge.domain.port.spi.AllocationProcessingRepository
import com.tequipy.challenge.domain.port.spi.InventoryReservationPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProcessAllocationService(
    private val inventoryReservationPort: InventoryReservationPort,
    private val allocationProcessingRepository: AllocationProcessingRepository,
    private val allocationEventPublisher: AllocationEventPublisher
) : ProcessAllocationUseCase {
    private val logger = KotlinLogging.logger {}

    @Transactional
    override fun processAllocation(command: ProcessAllocationCommand) {
        logger.info { "Processing allocation: id=${command.allocationId}" }

        if (!allocationProcessingRepository.tryStart(command.allocationId)) {
            val existing = allocationProcessingRepository.findById(command.allocationId)
                ?: throw AllocationLockContentionException(command.allocationId)

            when (existing.state) {
                AllocationProcessingState.PROCESSING -> throw AllocationLockContentionException(command.allocationId)
                AllocationProcessingState.ALLOCATED -> {
                    logger.info { "Allocation ${command.allocationId} already processed successfully, republishing cached result" }
                    allocationEventPublisher.publishAllocationProcessed(command.allocationId, true, existing.allocatedEquipmentIds)
                    return
                }
                AllocationProcessingState.FAILED -> {
                    logger.info { "Allocation ${command.allocationId} already processed as failed, republishing cached result" }
                    allocationEventPublisher.publishAllocationProcessed(command.allocationId, false)
                    return
                }
            }
        }

        val reservedEquipmentIds = inventoryReservationPort.reserveForAllocation(command.allocationId, command.policy)

        if (reservedEquipmentIds == null) {
            logger.warn { "Allocation ${command.allocationId} failed: no candidate equipment found for policy" }
            allocationProcessingRepository.complete(command.allocationId, AllocationProcessingState.FAILED, emptyList())
            allocationEventPublisher.publishAllocationProcessed(command.allocationId, success = false)
            return
        }
        allocationProcessingRepository.complete(
            command.allocationId,
            AllocationProcessingState.ALLOCATED,
            reservedEquipmentIds
        )
        allocationEventPublisher.publishAllocationProcessed(
            command.allocationId,
            success = true,
            allocatedEquipmentIds = reservedEquipmentIds
        )
        logger.info {
            "Allocation ${command.allocationId} processed successfully: reserved ${reservedEquipmentIds.size} equipment item(s)"
        }
    }
}

