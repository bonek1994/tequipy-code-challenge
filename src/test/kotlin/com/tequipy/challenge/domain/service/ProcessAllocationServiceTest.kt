package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.AllocationProcessingRecord
import com.tequipy.challenge.domain.model.AllocationProcessingState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.api.ProcessAllocationCommand
import com.tequipy.challenge.domain.port.spi.InventoryAllocationPort
import com.tequipy.challenge.domain.port.spi.AllocationEventPublisher
import com.tequipy.challenge.domain.port.spi.AllocationProcessingRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ProcessAllocationServiceTest {

    private val inventoryAllocationPort: InventoryAllocationPort = mockk()
    private val allocationProcessingRepository: AllocationProcessingRepository = mockk()
    private val allocationEventPublisher: AllocationEventPublisher = mockk(relaxed = true)
    private val service = ProcessAllocationService(inventoryAllocationPort, allocationProcessingRepository, allocationEventPublisher)

    @Test
    fun `processAllocation should publish failure when no selection is possible`() {
        val allocationId = UUID.randomUUID()
        val command = allocationCommand(
            allocationId = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1, minimumConditionScore = 0.9))
        )
        every { allocationProcessingRepository.tryStart(allocationId) } returns true
        every { inventoryAllocationPort.reserveForAllocation(allocationId, any()) } returns null
        every { allocationProcessingRepository.complete(allocationId, AllocationProcessingState.FAILED, emptyList()) } returns
            AllocationProcessingRecord(allocationId, AllocationProcessingState.FAILED)

        service.processAllocation(command)

        verify { inventoryAllocationPort.reserveForAllocation(allocationId, any()) }
        verify { allocationEventPublisher.publishAllocationProcessed(allocationId, false, emptyList()) }
    }

    @Test
    fun `processAllocation should throw AllocationLockContentionException when all candidates are locked by concurrent requests`() {
        val allocationId = UUID.randomUUID()
        val command = allocationCommand(
            allocationId = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1, minimumConditionScore = 0.8))
        )
        every { allocationProcessingRepository.tryStart(allocationId) } returns true
        every {
            inventoryAllocationPort.reserveForAllocation(allocationId, any())
        } throws com.tequipy.challenge.domain.AllocationLockContentionException(allocationId)

        assertThrows(com.tequipy.challenge.domain.AllocationLockContentionException::class.java) {
            service.processAllocation(command)
        }
        verify(exactly = 0) { allocationEventPublisher.publishAllocationProcessed(any(), any(), any()) }
    }

    @Test
    fun `processAllocation should throw AllocationLockContentionException when some candidates are locked by concurrent requests`() {
        val allocationId = UUID.randomUUID()
        val command = allocationCommand(
            allocationId = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 2, minimumConditionScore = 0.8))
        )
        every { allocationProcessingRepository.tryStart(allocationId) } returns true
        every {
            inventoryAllocationPort.reserveForAllocation(allocationId, any())
        } throws com.tequipy.challenge.domain.AllocationLockContentionException(allocationId)

        assertThrows(com.tequipy.challenge.domain.AllocationLockContentionException::class.java) {
            service.processAllocation(command)
        }
        verify(exactly = 0) { allocationEventPublisher.publishAllocationProcessed(any(), any(), any()) }
    }

    @Test
    fun `processAllocation should reserve selected equipment and publish success`() {
        val allocationId = UUID.randomUUID()
        val selectedEquipmentId = UUID.randomUUID()
        val command = allocationCommand(
            allocationId = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1, minimumConditionScore = 0.8))
        )
        every { allocationProcessingRepository.tryStart(allocationId) } returns true
        every { inventoryAllocationPort.reserveForAllocation(allocationId, any()) } returns listOf(selectedEquipmentId)
        every {
            allocationProcessingRepository.complete(
                allocationId,
                AllocationProcessingState.ALLOCATED,
                listOf(selectedEquipmentId)
            )
        } returns AllocationProcessingRecord(
            allocationId = allocationId,
            state = AllocationProcessingState.ALLOCATED,
            allocatedEquipmentIds = listOf(selectedEquipmentId)
        )

        service.processAllocation(command)

        verify { inventoryAllocationPort.reserveForAllocation(allocationId, any()) }
        verify {
            allocationEventPublisher.publishAllocationProcessed(allocationId, true, listOf(selectedEquipmentId))
        }
    }

    @Test
    fun `processAllocation should republish cached result for duplicate request`() {
        val allocationId = UUID.randomUUID()
        val reservedEquipmentId = UUID.randomUUID()
        val command = allocationCommand(
            allocationId = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1))
        )

        every { allocationProcessingRepository.tryStart(allocationId) } returns false
        every { allocationProcessingRepository.findById(allocationId) } returns AllocationProcessingRecord(
            allocationId = allocationId,
            state = AllocationProcessingState.ALLOCATED,
            allocatedEquipmentIds = listOf(reservedEquipmentId)
        )

        service.processAllocation(command)

        verify(exactly = 0) { inventoryAllocationPort.reserveForAllocation(any(), any()) }
        verify { allocationEventPublisher.publishAllocationProcessed(allocationId, true, listOf(reservedEquipmentId)) }
    }

    private fun allocationCommand(
        allocationId: UUID = UUID.randomUUID(),
        policy: List<EquipmentPolicyRequirement>
    ) = ProcessAllocationCommand(
        allocationId = allocationId,
        policy = policy
    )
}

