package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.command.CompleteAllocationCommand
import com.tequipy.challenge.domain.model.AllocationCompletion
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
    fun `completeAllocations should apply ALLOCATED state when success is true`() {
        val allocationId = UUID.randomUUID()
        val equipmentIds = listOf(UUID.randomUUID(), UUID.randomUUID())

        every {
            allocationRepository.completePendingBatch(
                listOf(
                    AllocationCompletion(
                        allocationId = allocationId,
                        state = AllocationState.ALLOCATED,
                        allocatedEquipmentIds = equipmentIds
                    )
                )
            )
        } returns listOf(
            AllocationEntity(
                id = allocationId,
                policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
                state = AllocationState.ALLOCATED,
                allocatedEquipmentIds = equipmentIds
            )
        )

        service.completeAllocations(
            listOf(
                CompleteAllocationCommand(
                    allocationId = allocationId,
                    success = true,
                    allocatedEquipmentIds = equipmentIds
                )
            )
        )

        verify {
            allocationRepository.completePendingBatch(
                listOf(
                    AllocationCompletion(
                        allocationId = allocationId,
                        state = AllocationState.ALLOCATED,
                        allocatedEquipmentIds = equipmentIds
                    )
                )
            )
        }
    }

    @Test
    fun `completeAllocations should apply FAILED state with empty equipment list when success is false`() {
        val allocationId = UUID.randomUUID()

        every {
            allocationRepository.completePendingBatch(
                listOf(
                    AllocationCompletion(
                        allocationId = allocationId,
                        state = AllocationState.FAILED,
                        allocatedEquipmentIds = emptyList()
                    )
                )
            )
        } returns listOf(
            AllocationEntity(
                id = allocationId,
                policy = listOf(EquipmentPolicyRequirement(EquipmentType.KEYBOARD, quantity = 2)),
                state = AllocationState.FAILED,
                allocatedEquipmentIds = emptyList()
            )
        )

        service.completeAllocations(
            listOf(
                CompleteAllocationCommand(
                    allocationId = allocationId,
                    success = false,
                    allocatedEquipmentIds = listOf(UUID.randomUUID())
                )
            )
        )

        verify {
            allocationRepository.completePendingBatch(
                listOf(
                    AllocationCompletion(
                        allocationId = allocationId,
                        state = AllocationState.FAILED,
                        allocatedEquipmentIds = emptyList()
                    )
                )
            )
        }
    }

    @Test
    fun `completeAllocations should not throw when allocation is missing or no longer pending`() {
        val allocationId = UUID.randomUUID()
        val equipmentIds = listOf(UUID.randomUUID())

        every {
            allocationRepository.completePendingBatch(any())
        } returns emptyList()

        service.completeAllocations(
            listOf(
                CompleteAllocationCommand(
                    allocationId = allocationId,
                    success = true,
                    allocatedEquipmentIds = equipmentIds
                )
            )
        )

        verify {
            allocationRepository.completePendingBatch(
                listOf(
                    AllocationCompletion(
                        allocationId = allocationId,
                        state = AllocationState.ALLOCATED,
                        allocatedEquipmentIds = equipmentIds
                    )
                )
            )
        }
    }

    @Test
    fun `completeAllocations should persist multiple results in one batch call`() {
        val firstAllocationId = UUID.randomUUID()
        val secondAllocationId = UUID.randomUUID()
        val equipmentIds = listOf(UUID.randomUUID())

        every {
            allocationRepository.completePendingBatch(
                listOf(
                    AllocationCompletion(
                        allocationId = firstAllocationId,
                        state = AllocationState.ALLOCATED,
                        allocatedEquipmentIds = equipmentIds
                    ),
                    AllocationCompletion(
                        allocationId = secondAllocationId,
                        state = AllocationState.FAILED,
                        allocatedEquipmentIds = emptyList()
                    )
                )
            )
        } returns listOf(
            AllocationEntity(
                id = firstAllocationId,
                policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
                state = AllocationState.ALLOCATED,
                allocatedEquipmentIds = equipmentIds
            ),
            AllocationEntity(
                id = secondAllocationId,
                policy = listOf(EquipmentPolicyRequirement(EquipmentType.KEYBOARD, quantity = 1)),
                state = AllocationState.FAILED,
                allocatedEquipmentIds = emptyList()
            )
        )

        service.completeAllocations(
            listOf(
                CompleteAllocationCommand(
                    allocationId = firstAllocationId,
                    success = true,
                    allocatedEquipmentIds = equipmentIds
                ),
                CompleteAllocationCommand(
                    allocationId = secondAllocationId,
                    success = false,
                    allocatedEquipmentIds = listOf(UUID.randomUUID())
                )
            )
        )

        verify(exactly = 1) {
            allocationRepository.completePendingBatch(
                listOf(
                    AllocationCompletion(
                        allocationId = firstAllocationId,
                        state = AllocationState.ALLOCATED,
                        allocatedEquipmentIds = equipmentIds
                    ),
                    AllocationCompletion(
                        allocationId = secondAllocationId,
                        state = AllocationState.FAILED,
                        allocatedEquipmentIds = emptyList()
                    )
                )
            )
        }
    }
}


