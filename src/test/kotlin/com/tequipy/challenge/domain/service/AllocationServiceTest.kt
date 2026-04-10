package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.event.AllocationCreatedEvent
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.out.AllocationRepository
import com.tequipy.challenge.domain.port.out.EquipmentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import java.util.UUID

class AllocationServiceTest {
    private val allocationRepository: AllocationRepository = mockk()
    private val equipmentRepository: EquipmentRepository = mockk()
    private val eventPublisher: ApplicationEventPublisher = mockk(relaxed = true)
    private val allocationProcessor: AllocationProcessor = mockk(relaxed = true)

    private val service = AllocationService(allocationRepository, equipmentRepository, eventPublisher, allocationProcessor)

    @Test
    fun `createAllocation should persist pending request and publish event`() {
        // given
        val employeeId = UUID.randomUUID()
        val captured = slot<AllocationRequest>()
        val eventSlot = slot<Any>()
        val saved = AllocationRequest(
            id = UUID.randomUUID(),
            employeeId = employeeId,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.PENDING,
            allocatedEquipmentIds = emptyList()
        )
        every { allocationRepository.save(capture(captured)) } returns saved
        every { allocationRepository.findById(saved.id) } returns saved
        every { eventPublisher.publishEvent(capture(eventSlot)) } returns Unit

        // when
        val result = service.createAllocation(employeeId, saved.policy)

        // then
        assertEquals(AllocationState.PENDING, captured.captured.state)
        assertEquals(saved, result)
        assertEquals(saved.id, (eventSlot.captured as AllocationCreatedEvent).allocationId)
    }

    @Test
    fun `createAllocation should reject empty policy`() {
        // when / then
        assertThrows(BadRequestException::class.java) {
            service.createAllocation(UUID.randomUUID(), emptyList())
        }
    }

    @Test
    fun `createAllocation should reject non positive quantity`() {
        // when / then
        assertThrows(BadRequestException::class.java) {
            service.createAllocation(
                UUID.randomUUID(),
                listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 0))
            )
        }
    }

    @Test
    fun `createAllocation should reject invalid minimum condition score`() {
        // when / then
        assertThrows(BadRequestException::class.java) {
            service.createAllocation(
                UUID.randomUUID(),
                listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, minimumConditionScore = 1.1))
            )
        }
    }

    @Test
    fun `getAllocation should throw when missing`() {
        // given
        val allocationId = UUID.randomUUID()
        every { allocationRepository.findById(allocationId) } returns null

        // when / then
        assertThrows(NotFoundException::class.java) {
            service.getAllocation(allocationId)
        }
    }

    @Test
    fun `confirmAllocation should move reserved equipment to assigned`() {
        // given
        val allocationId = UUID.randomUUID()
        val equipment = equipment(id = UUID.randomUUID(), state = EquipmentState.RESERVED)
        val allocation = AllocationRequest(
            id = allocationId,
            employeeId = UUID.randomUUID(),
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.ALLOCATED,
            allocatedEquipmentIds = listOf(equipment.id)
        )
        val confirmed = allocation.copy(state = AllocationState.CONFIRMED)
        every { allocationRepository.findById(allocationId) } returns allocation
        every { equipmentRepository.findByIds(listOf(equipment.id)) } returns listOf(equipment)
        every { equipmentRepository.saveAll(any()) } answers { firstArg() }
        every { allocationRepository.save(any()) } returns confirmed

        // when
        val result = service.confirmAllocation(allocationId)

        // then
        assertEquals(AllocationState.CONFIRMED, result.state)
        verify { equipmentRepository.saveAll(match { it.all { eq -> eq.state == EquipmentState.ASSIGNED } }) }
    }

    @Test
    fun `cancelAllocation should release reserved equipment`() {
        // given
        val allocationId = UUID.randomUUID()
        val equipment = equipment(id = UUID.randomUUID(), state = EquipmentState.RESERVED)
        val allocation = AllocationRequest(
            id = allocationId,
            employeeId = UUID.randomUUID(),
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.ALLOCATED,
            allocatedEquipmentIds = listOf(equipment.id)
        )
        val cancelled = allocation.copy(state = AllocationState.CANCELLED, allocatedEquipmentIds = emptyList())
        every { allocationRepository.findById(allocationId) } returns allocation
        every { equipmentRepository.findByIds(listOf(equipment.id)) } returns listOf(equipment)
        every { equipmentRepository.saveAll(any()) } answers { firstArg() }
        every { allocationRepository.save(any()) } returns cancelled

        // when
        val result = service.cancelAllocation(allocationId)

        // then
        assertEquals(AllocationState.CANCELLED, result.state)
        verify { equipmentRepository.saveAll(match { it.all { eq -> eq.state == EquipmentState.AVAILABLE } }) }
    }

    @Test
    fun `cancelAllocation should cancel pending allocation without touching equipment`() {
        // given
        val allocationId = UUID.randomUUID()
        val allocation = AllocationRequest(
            id = allocationId,
            employeeId = UUID.randomUUID(),
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.PENDING,
            allocatedEquipmentIds = emptyList()
        )
        val cancelled = allocation.copy(state = AllocationState.CANCELLED, allocatedEquipmentIds = emptyList())
        every { allocationRepository.findById(allocationId) } returns allocation
        every { allocationRepository.save(any()) } returns cancelled

        // when
        val result = service.cancelAllocation(allocationId)

        // then
        assertEquals(AllocationState.CANCELLED, result.state)
        verify(exactly = 0) { equipmentRepository.findByIds(any()) }
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
    }

    @Test
    fun `cancelAllocation should cancel failed allocation without touching equipment`() {
        // given
        val allocationId = UUID.randomUUID()
        val allocation = AllocationRequest(
            id = allocationId,
            employeeId = UUID.randomUUID(),
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.FAILED,
            allocatedEquipmentIds = emptyList()
        )
        val cancelled = allocation.copy(state = AllocationState.CANCELLED, allocatedEquipmentIds = emptyList())
        every { allocationRepository.findById(allocationId) } returns allocation
        every { allocationRepository.save(any()) } returns cancelled

        // when
        val result = service.cancelAllocation(allocationId)

        // then
        assertEquals(AllocationState.CANCELLED, result.state)
        verify(exactly = 0) { equipmentRepository.findByIds(any()) }
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
    }

    @Test
    fun `cancelAllocation should throw for confirmed allocation`() {
        // given
        val allocationId = UUID.randomUUID()
        every { allocationRepository.findById(allocationId) } returns AllocationRequest(
            id = allocationId,
            employeeId = UUID.randomUUID(),
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.CONFIRMED,
            allocatedEquipmentIds = emptyList()
        )

        // when / then
        assertThrows(ConflictException::class.java) {
            service.cancelAllocation(allocationId)
        }
    }

    @Test
    fun `cancelAllocation should keep non reserved equipment unchanged`() {
        // given
        val allocationId = UUID.randomUUID()
        val equipment = equipment(id = UUID.randomUUID(), state = EquipmentState.ASSIGNED)
        val allocation = AllocationRequest(
            id = allocationId,
            employeeId = UUID.randomUUID(),
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
            state = AllocationState.ALLOCATED,
            allocatedEquipmentIds = listOf(equipment.id)
        )
        val cancelled = allocation.copy(state = AllocationState.CANCELLED, allocatedEquipmentIds = emptyList())
        every { allocationRepository.findById(allocationId) } returns allocation
        every { equipmentRepository.findByIds(listOf(equipment.id)) } returns listOf(equipment)
        every { equipmentRepository.saveAll(any()) } answers { firstArg() }
        every { allocationRepository.save(any()) } returns cancelled

        // when
        service.cancelAllocation(allocationId)

        // then
        verify {
            equipmentRepository.saveAll(match { saved ->
                saved.size == 1 && saved.single().id == equipment.id && saved.single().state == EquipmentState.ASSIGNED
            })
        }
    }

    @Test
    fun `confirmAllocation should fail for non allocated request`() {
        // given
        val allocationId = UUID.randomUUID()
        every { allocationRepository.findById(allocationId) } returns AllocationRequest(
            id = allocationId,
            employeeId = UUID.randomUUID(),
            policy = emptyList(),
            state = AllocationState.PENDING,
            allocatedEquipmentIds = emptyList()
        )

        // when / then
        assertThrows(ConflictException::class.java) {
            service.confirmAllocation(allocationId)
        }
    }

    private fun equipment(id: UUID, state: EquipmentState) = Equipment(
        id = id,
        type = EquipmentType.MONITOR,
        brand = "Dell",
        model = "U2723QE",
        state = state,
        conditionScore = 0.9,
        purchaseDate = LocalDate.of(2024, 1, 1),
        retiredReason = null
    )
}




