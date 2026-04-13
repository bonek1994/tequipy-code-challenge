package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.domain.command.ProcessAllocationCommand
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.service.BatchAllocationCollector
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class AllocationMessageListenerTest {

    private val batchAllocationCollector: BatchAllocationCollector = mockk(relaxed = true)
    private val listener = AllocationMessageListener(batchAllocationCollector)

    @Test
    fun `onAllocationCreated should submit command to batch collector`() {
        // given
        val allocationId = UUID.randomUUID()
        val message = AllocationRequestedMessage(
            id = allocationId,
            policy = listOf(
                AllocationRequestedMessage.PolicyRequirementMessage(type = EquipmentType.MONITOR, quantity = 1)
            )
        )

        // when
        listener.onAllocationCreated(message)

        // then
        verify {
            batchAllocationCollector.submit(match { command: ProcessAllocationCommand ->
                command.allocationId == allocationId &&
                    command.policy.size == 1 &&
                    command.policy.single().type == EquipmentType.MONITOR
            })
        }
    }
}
