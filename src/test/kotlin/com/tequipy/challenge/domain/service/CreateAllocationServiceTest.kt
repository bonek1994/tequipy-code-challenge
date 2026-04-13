package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.command.CreateAllocationCommand
import com.tequipy.challenge.domain.model.AllocationEntity
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.spi.AllocationEventPublisher
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class CreateAllocationServiceTest {
    private val allocationRepository: AllocationRepository = mockk()
    private val allocationEventPublisher: AllocationEventPublisher = mockk(relaxed = true)

    private val service = CreateAllocationService(allocationRepository, allocationEventPublisher)

    @Test
    fun `createAllocation should persist pending request and publish event`() {
        val captured = slot<AllocationEntity>()
        val saved = AllocationEntity(
            id = UUID.randomUUID(),
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.PENDING,
            allocatedEquipmentIds = emptyList()
        )
        every { allocationRepository.save(capture(captured)) } returns saved

        val result = service.createAllocation(CreateAllocationCommand(saved.policy))

        assertEquals(AllocationState.PENDING, captured.captured.state)
        assertEquals(saved, result)
        verify { allocationEventPublisher.publishAllocationCreated(saved) }
    }

    @Test
    fun `createAllocation should reject empty policy`() {
        assertThrows(BadRequestException::class.java) {
            service.createAllocation(CreateAllocationCommand(emptyList()))
        }
    }

    @Test
    fun `createAllocation should reject non positive quantity`() {
        assertThrows(BadRequestException::class.java) {
            service.createAllocation(CreateAllocationCommand(listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 0))))
        }
    }

    @Test
    fun `createAllocation should reject invalid minimum condition score`() {
        assertThrows(BadRequestException::class.java) {
            service.createAllocation(CreateAllocationCommand(listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, minimumConditionScore = 1.1))))
        }
    }
}


