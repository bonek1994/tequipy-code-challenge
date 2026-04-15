package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.adapter.api.messaging.events.EquipmentAllocated
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
    fun `onAllocationProcessed should delegate batch results to use case`() {
        val firstAllocationId = UUID.randomUUID()
        val secondAllocationId = UUID.randomUUID()
        val equipmentIds = listOf(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        val staleEquipmentIds = listOf(UUID.randomUUID())

        listener.onAllocationProcessed(
            EquipmentAllocated(
                results = listOf(
                    EquipmentAllocated.AllocationProcessedResultMessage(
                        id = firstAllocationId,
                        success = true,
                        allocatedEquipmentIds = equipmentIds
                    ),
                    EquipmentAllocated.AllocationProcessedResultMessage(
                        id = secondAllocationId,
                        success = false,
                        allocatedEquipmentIds = staleEquipmentIds
                    )
                )
            )
        )

        verify {
            completeAllocationUseCase.completeAllocations(
                listOf(
                    CompleteAllocationCommand(
                        allocationId = firstAllocationId,
                        success = true,
                        allocatedEquipmentIds = equipmentIds
                    ),
                    CompleteAllocationCommand(
                        allocationId = secondAllocationId,
                        success = false,
                        allocatedEquipmentIds = staleEquipmentIds
                    )
                )
            )
        }
    }
}


