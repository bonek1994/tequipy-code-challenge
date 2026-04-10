package com.tequipy.challenge.adapter.messaging

import com.tequipy.challenge.domain.service.AllocationProcessor
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class AllocationMessageListenerTest {

    private val allocationProcessor: AllocationProcessor = mockk(relaxed = true)
    private val listener = AllocationMessageListener(allocationProcessor)

    @Test
    fun `onAllocationCreated should invoke processor with parsed UUID`() {
        // given
        val allocationId = UUID.randomUUID()

        // when
        listener.onAllocationCreated(allocationId.toString())

        // then
        verify { allocationProcessor.processAllocation(allocationId) }
    }
}
