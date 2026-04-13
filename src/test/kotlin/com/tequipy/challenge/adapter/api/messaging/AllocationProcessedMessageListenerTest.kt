package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.domain.command.CompleteAllocationCommand
import com.tequipy.challenge.domain.port.api.CompleteAllocationUseCase
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class AllocationProcessedMessageListenerTest {

    private val completeAllocationUseCase: CompleteAllocationUseCase = mockk(relaxed = true)
    private val listener = AllocationProcessedMessageListener(completeAllocationUseCase)

    @Test
    fun `onAllocationProcessed should delegate successful result to use case`() {
        val allocationId = UUID.randomUUID()
        val equipmentIds = listOf(UUID.fromString("11111111-1111-1111-1111-111111111111"))

        listener.onAllocationProcessed(
            AllocationProcessedMessage(
                id = allocationId,
                success = true,
                allocatedEquipmentIds = equipmentIds
            )
        )

        verify {
            completeAllocationUseCase.completeAllocation(
                CompleteAllocationCommand(
                    allocationId = allocationId,
                    success = true,
                    allocatedEquipmentIds = equipmentIds
                )
            )
        }
    }

    @Test
    fun `onAllocationProcessed should delegate failed result to use case`() {
        val allocationId = UUID.randomUUID()
        val staleEquipmentIds = listOf(UUID.randomUUID())

        listener.onAllocationProcessed(
            AllocationProcessedMessage(
                id = allocationId,
                success = false,
                allocatedEquipmentIds = staleEquipmentIds
            )
        )

        verify {
            completeAllocationUseCase.completeAllocation(
                CompleteAllocationCommand(
                    allocationId = allocationId,
                    success = false,
                    allocatedEquipmentIds = staleEquipmentIds
                )
            )
        }
    }
}


