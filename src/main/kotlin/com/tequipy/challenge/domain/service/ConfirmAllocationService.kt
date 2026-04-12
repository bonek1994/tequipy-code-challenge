package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.port.api.ConfirmAllocationUseCase
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import com.tequipy.challenge.domain.port.spi.InventoryReservationPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class ConfirmAllocationService(
    private val allocationRepository: AllocationRepository,
    private val inventoryReservationPort: InventoryReservationPort
) : ConfirmAllocationUseCase {

    private val logger = KotlinLogging.logger {}

    override fun confirmAllocation(id: UUID): AllocationRequest {
        val allocation = requireAllocation(id)
        if (allocation.state != AllocationState.ALLOCATED) {
            throw ConflictException("Only allocated requests can be confirmed")
        }

        logger.info { "Confirming allocation: id=$id" }
        inventoryReservationPort.confirmReservedEquipment(allocation.allocatedEquipmentIds)

        val confirmed = allocationRepository.save(allocation.copy(state = AllocationState.CONFIRMED))
        logger.info { "Allocation confirmed: id=$id" }
        return confirmed
    }

    private fun requireAllocation(id: UUID): AllocationRequest {
        return allocationRepository.findById(id)
            ?: throw NotFoundException("Allocation not found with id: $id")
    }
}

