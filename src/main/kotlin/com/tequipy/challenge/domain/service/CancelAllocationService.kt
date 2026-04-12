package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.port.api.CancelAllocationUseCase
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import com.tequipy.challenge.domain.port.spi.InventoryReservationPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class CancelAllocationService(
    private val allocationRepository: AllocationRepository,
    private val inventoryReservationPort: InventoryReservationPort
) : CancelAllocationUseCase {

    private val logger = KotlinLogging.logger {}

    override fun cancelAllocation(id: UUID): AllocationRequest {
        val allocation = requireAllocation(id)
        if (allocation.state !in setOf(AllocationState.PENDING, AllocationState.ALLOCATED, AllocationState.FAILED)) {
            throw ConflictException("Only pending, allocated or failed requests can be cancelled")
        }

        logger.info { "Cancelling allocation: id=$id" }
        if (allocation.allocatedEquipmentIds.isNotEmpty()) {
            inventoryReservationPort.releaseReservedEquipment(allocation.allocatedEquipmentIds)
        }

        val cancelled = allocationRepository.save(
            allocation.copy(
                state = AllocationState.CANCELLED,
                allocatedEquipmentIds = emptyList()
            )
        )
        logger.info { "Allocation cancelled: id=$id" }
        return cancelled
    }

    private fun requireAllocation(id: UUID): AllocationRequest {
        return allocationRepository.findById(id)
            ?: throw NotFoundException("Allocation not found with id: $id")
    }
}

