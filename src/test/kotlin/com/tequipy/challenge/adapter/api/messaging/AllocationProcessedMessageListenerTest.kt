package com.tequipy.challenge.adapter.api.messaging

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

class AllocationProcessedMessageListenerTest {

    private val allocationRepository: AllocationRepository = mockk()
    private val listener = AllocationProcessedMessageListener(allocationRepository)

    @Test
    fun `onAllocationProcessed should apply allocated result to pending allocation`() {
        val allocationId = UUID.randomUUID()
        every {
            allocationRepository.completePending(allocationId, AllocationState.ALLOCATED, listOf(UUID.fromString("11111111-1111-1111-1111-111111111111")))
        } returns AllocationEntity(
            id = allocationId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.ALLOCATED,
            allocatedEquipmentIds = listOf(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        )

        listener.onAllocationProcessed(
            AllocationProcessedMessage(
                id = allocationId,
                success = true,
                allocatedEquipmentIds = listOf(UUID.fromString("11111111-1111-1111-1111-111111111111"))
            )
        )

        verify {
            allocationRepository.completePending(
                allocationId,
                AllocationState.ALLOCATED,
                listOf(UUID.fromString("11111111-1111-1111-1111-111111111111"))
            )
        }
    }

    @Test
    fun `onAllocationProcessed should ignore duplicate or stale result`() {
        val allocationId = UUID.randomUUID()
        every { allocationRepository.completePending(allocationId, AllocationState.FAILED, emptyList()) } returns null

        listener.onAllocationProcessed(
            AllocationProcessedMessage(
                id = allocationId,
                success = false,
                allocatedEquipmentIds = listOf(UUID.randomUUID())
            )
        )

        verify { allocationRepository.completePending(allocationId, AllocationState.FAILED, emptyList()) }
    }
}


