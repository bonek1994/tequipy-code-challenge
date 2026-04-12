package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import com.tequipy.challenge.domain.port.spi.InventoryReservationPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class CancelAllocationServiceTest {
    private val allocationRepository: AllocationRepository = mockk()
    private val inventoryReservationPort: InventoryReservationPort = mockk(relaxed = true)
    private val service = CancelAllocationService(allocationRepository, inventoryReservationPort)

    @Test
    fun `cancelAllocation should release reserved equipment`() {
        val allocationId = UUID.randomUUID()
        val equipmentId = UUID.randomUUID()
        val allocation = AllocationRequest(
            id = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.ALLOCATED,
            allocatedEquipmentIds = listOf(equipmentId)
        )
        val cancelled = allocation.copy(state = AllocationState.CANCELLED, allocatedEquipmentIds = emptyList())
        every { allocationRepository.findById(allocationId) } returns allocation
        every { allocationRepository.save(any()) } returns cancelled

        val result = service.cancelAllocation(allocationId)

        assertEquals(AllocationState.CANCELLED, result.state)
        verify { inventoryReservationPort.releaseReservedEquipment(listOf(equipmentId)) }
    }

    @Test
    fun `cancelAllocation should cancel pending allocation without touching equipment`() {
        val allocationId = UUID.randomUUID()
        val allocation = AllocationRequest(
            id = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.PENDING,
            allocatedEquipmentIds = emptyList()
        )
        val cancelled = allocation.copy(state = AllocationState.CANCELLED, allocatedEquipmentIds = emptyList())
        every { allocationRepository.findById(allocationId) } returns allocation
        every { allocationRepository.save(any()) } returns cancelled

        val result = service.cancelAllocation(allocationId)

        assertEquals(AllocationState.CANCELLED, result.state)
        verify(exactly = 0) { inventoryReservationPort.releaseReservedEquipment(any()) }
    }

    @Test
    fun `cancelAllocation should cancel failed allocation without touching equipment`() {
        val allocationId = UUID.randomUUID()
        val allocation = AllocationRequest(
            id = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.FAILED,
            allocatedEquipmentIds = emptyList()
        )
        val cancelled = allocation.copy(state = AllocationState.CANCELLED, allocatedEquipmentIds = emptyList())
        every { allocationRepository.findById(allocationId) } returns allocation
        every { allocationRepository.save(any()) } returns cancelled

        val result = service.cancelAllocation(allocationId)

        assertEquals(AllocationState.CANCELLED, result.state)
        verify(exactly = 0) { inventoryReservationPort.releaseReservedEquipment(any()) }
    }

    @Test
    fun `cancelAllocation should throw for confirmed allocation`() {
        val allocationId = UUID.randomUUID()
        every { allocationRepository.findById(allocationId) } returns AllocationRequest(
            id = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.CONFIRMED,
            allocatedEquipmentIds = emptyList()
        )

        assertThrows(ConflictException::class.java) {
            service.cancelAllocation(allocationId)
        }
    }

    @Test
    fun `cancelAllocation should delegate release for allocated equipment ids`() {
        val allocationId = UUID.randomUUID()
        val equipmentId = UUID.randomUUID()
        val allocation = AllocationRequest(
            id = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.ALLOCATED,
            allocatedEquipmentIds = listOf(equipmentId)
        )
        val cancelled = allocation.copy(state = AllocationState.CANCELLED, allocatedEquipmentIds = emptyList())
        every { allocationRepository.findById(allocationId) } returns allocation
        every { allocationRepository.save(any()) } returns cancelled

        service.cancelAllocation(allocationId)

        verify { inventoryReservationPort.releaseReservedEquipment(listOf(equipmentId)) }
    }
}

