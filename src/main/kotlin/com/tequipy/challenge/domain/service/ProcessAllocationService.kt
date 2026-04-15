package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.AllocationLockContentionException
import com.tequipy.challenge.domain.command.ProcessAllocationCommand
import com.tequipy.challenge.domain.model.AllocationProcessingState
import com.tequipy.challenge.domain.port.api.ProcessAllocationUseCase
import com.tequipy.challenge.domain.port.spi.AllocationEventPublisher
import com.tequipy.challenge.domain.port.spi.AllocationProcessingRepository
import com.tequipy.challenge.domain.port.spi.InventoryAllocationPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProcessAllocationService(
    private val inventoryAllocationPort: InventoryAllocationPort,
    private val allocationProcessingRepository: AllocationProcessingRepository,
    private val allocationEventPublisher: AllocationEventPublisher
) : ProcessAllocationUseCase {

    private val logger = KotlinLogging.logger {}

    @Transactional
    override fun processAllocation(command: ProcessAllocationCommand) {
        val id = command.allocationId
        logger.info { "Processing allocation: id=$id" }

        if (!allocationProcessingRepository.tryStart(id)) {
            republishCachedResult(id)
            return
        }

        val reservedIds = inventoryAllocationPort.reserveForAllocation(id, command.policy)
        if (reservedIds == null) {
            logger.warn { "Allocation $id failed: no candidate equipment found" }
            allocationProcessingRepository.complete(id, AllocationProcessingState.FAILED, emptyList())
            allocationEventPublisher.publishAllocationProcessed(id, success = false)
            return
        }

        allocationProcessingRepository.complete(id, AllocationProcessingState.ALLOCATED, reservedIds)
        allocationEventPublisher.publishAllocationProcessed(id, success = true, reservedIds)
        logger.info { "Allocation $id processed: reserved ${reservedIds.size} item(s)" }
    }

    private fun republishCachedResult(id: UUID) {
        val existing = allocationProcessingRepository.findById(id)
            ?: throw AllocationLockContentionException(id)

        when (existing.state) {
            AllocationProcessingState.PROCESSING ->
                throw AllocationLockContentionException(id)
            AllocationProcessingState.ALLOCATED -> {
                logger.info { "Allocation $id already processed, republishing cached result" }
                allocationEventPublisher.publishAllocationProcessed(id, true, existing.allocatedEquipmentIds)
            }
            AllocationProcessingState.FAILED -> {
                logger.info { "Allocation $id already failed, republishing cached result" }
                allocationEventPublisher.publishAllocationProcessed(id, false)
            }
        }
    }
}
