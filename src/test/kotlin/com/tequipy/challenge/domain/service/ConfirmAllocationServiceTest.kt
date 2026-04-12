package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.model.AllocationEntity
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.spi.InventoryAllocationPort
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ConfirmAllocationServiceTest {
    private val allocationRepository: AllocationRepository = mockk()
    private val inventoryAllocationPort: InventoryAllocationPort = mockk(relaxed = true)
    private val service = ConfirmAllocationService(allocationRepository, inventoryAllocationPort)

    @Test
    fun `confirmAllocation should move reserved equipment to assigned`() {
        val allocationId = UUID.randomUUID()
        val equipmentId = UUID.randomUUID()
        val allocation = AllocationEntity(
            id = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.ALLOCATED,
            allocatedEquipmentIds = listOf(equipmentId)
        )
        val confirmed = allocation.copy(state = AllocationState.CONFIRMED)
        every { allocationRepository.findById(allocationId) } returns allocation
        every { allocationRepository.save(any()) } returns confirmed

        val result = service.confirmAllocation(allocationId)

        assertEquals(AllocationState.CONFIRMED, result.state)
        verify { inventoryAllocationPort.confirmReservedEquipment(listOf(equipmentId)) }
    }

    @Test
    fun `confirmAllocation should fail for non allocated request`() {
        val allocationId = UUID.randomUUID()
        every { allocationRepository.findById(allocationId) } returns AllocationEntity(
            id = allocationId,
            policy = emptyList(),
            state = AllocationState.PENDING,
            allocatedEquipmentIds = emptyList()
        )

        assertThrows(ConflictException::class.java) {
            service.confirmAllocation(allocationId)
        }
    }
}


