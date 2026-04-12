package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.domain.command.ProcessAllocationCommand
import com.tequipy.challenge.domain.port.api.ProcessAllocationUseCase
import com.tequipy.challenge.domain.model.EquipmentType
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class AllocationMessageListenerTest {

    private val processAllocationUseCase: ProcessAllocationUseCase = mockk(relaxed = true)
    private val listener = AllocationMessageListener(processAllocationUseCase)

    @Test
    fun `onAllocationCreated should invoke processor with allocation built from message`() {
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
            processAllocationUseCase.processAllocation(match { command: ProcessAllocationCommand ->
                command.allocationId == allocationId &&
                    command.policy.size == 1 &&
                    command.policy.single().type == EquipmentType.MONITOR
            })
        }
    }
}
