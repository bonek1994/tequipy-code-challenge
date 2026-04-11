package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.service.AllocationProcessor
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class AllocationMessageListenerTest {

    private val allocationProcessor: AllocationProcessor = mockk(relaxed = true)
    private val listener = AllocationMessageListener(allocationProcessor)

    @Test
    fun `onAllocationCreated should invoke processor with allocation built from message`() {
        // given
        val allocationId = UUID.randomUUID()
        val message = AllocationMessage(
            id = allocationId,
            policy = listOf(
                AllocationMessage.PolicyRequirementMessage(type = EquipmentType.MONITOR, quantity = 1)
            )
        )

        // when
        listener.onAllocationCreated(message)

        // then
        verify {
            allocationProcessor.processAllocation(match { allocation: AllocationRequest ->
                allocation.id == allocationId &&
                    allocation.state == AllocationState.PENDING &&
                    allocation.policy.size == 1 &&
                    allocation.policy.single().type == EquipmentType.MONITOR
            })
        }
    }
}
