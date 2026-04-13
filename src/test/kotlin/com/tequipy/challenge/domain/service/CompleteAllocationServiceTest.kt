package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.command.CompleteAllocationCommand
import com.tequipy.challenge.domain.model.AllocationEntity
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class CompleteAllocationServiceTest {

    private val allocationRepository: AllocationRepository = mockk()
    private val service = CompleteAllocationService(allocationRepository)

    @Test
    fun `completeAllocation should apply ALLOCATED state when success is true`() {
        val allocationId = UUID.randomUUID()
        val equipmentIds = listOf(UUID.randomUUID(), UUID.randomUUID())

        every {
            allocationRepository.completePending(allocationId, AllocationState.ALLOCATED, equipmentIds)
        } returns AllocationEntity(
            id = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.ALLOCATED,
            allocatedEquipmentIds = equipmentIds
        )

        service.completeAllocation(
            CompleteAllocationCommand(
                allocationId = allocationId,
                success = true,
                allocatedEquipmentIds = equipmentIds
            )
        )

        verify {
            allocationRepository.completePending(allocationId, AllocationState.ALLOCATED, equipmentIds)
        }
    }

    @Test
    fun `completeAllocation should apply FAILED state with empty equipment list when success is false`() {
        val allocationId = UUID.randomUUID()

        every {
            allocationRepository.completePending(allocationId, AllocationState.FAILED, emptyList())
        } returns AllocationEntity(
            id = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.KEYBOARD, quantity = 2)),
            state = AllocationState.FAILED,
            allocatedEquipmentIds = emptyList()
        )

        service.completeAllocation(
            CompleteAllocationCommand(
                allocationId = allocationId,
                success = false,
                allocatedEquipmentIds = listOf(UUID.randomUUID())
            )
        )

        verify {
            allocationRepository.completePending(allocationId, AllocationState.FAILED, emptyList())
        }
    }

    @Test
    fun `completeAllocation should not throw when allocation is missing or no longer pending`() {
        val allocationId = UUID.randomUUID()

        every {
            allocationRepository.completePending(allocationId, AllocationState.ALLOCATED, any())
        } returns null

        service.completeAllocation(
            CompleteAllocationCommand(
                allocationId = allocationId,
                success = true,
                allocatedEquipmentIds = listOf(UUID.randomUUID())
            )
        )

        verify {
            allocationRepository.completePending(allocationId, AllocationState.ALLOCATED, any())
        }
    }
}


