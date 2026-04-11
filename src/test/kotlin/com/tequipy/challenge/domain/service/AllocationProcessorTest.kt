package com.tequipy.challenge.domain.service

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
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class AllocationProcessorTest {

    private val allocationRepository: AllocationRepository = mockk()
    private val equipmentRepository: EquipmentRepository = mockk()
    private val processor = AllocationProcessor(allocationRepository, equipmentRepository)

    @Test
    fun `processAllocation should do nothing when allocation is missing`() {
        // given
        val allocationId = UUID.randomUUID()
        val pending = allocation(id = allocationId, state = AllocationState.PENDING, policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)))
        every { allocationRepository.findById(allocationId) } returns null

        // when
        processor.processAllocation(pending)

        // then
        verify(exactly = 0) { equipmentRepository.findAvailableWithMinConditionScore(any(), any()) }
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
        verify(exactly = 0) { allocationRepository.save(any()) }
    }

    @Test
    fun `processAllocation should ignore allocation that is not pending`() {
        // given
        val allocationId = UUID.randomUUID()
        val messageAllocation = allocation(
            id = allocationId,
            state = AllocationState.PENDING,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1))
        )
        every { allocationRepository.findById(allocationId) } returns allocation(
            id = allocationId,
            state = AllocationState.ALLOCATED,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1))
        )

        // when
        processor.processAllocation(messageAllocation)

        // then
        verify(exactly = 0) { equipmentRepository.findAvailableWithMinConditionScore(any(), any()) }
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
        verify(exactly = 0) { allocationRepository.save(any()) }
    }

    @Test
    fun `processAllocation should mark allocation as failed when no selection is possible`() {
        // given
        val allocationId = UUID.randomUUID()
        val pending = allocation(
            id = allocationId,
            state = AllocationState.PENDING,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, minimumConditionScore = 0.9))
        )
        every { allocationRepository.findById(allocationId) } returns pending
        every { equipmentRepository.findAvailableWithMinConditionScore(setOf(EquipmentType.MONITOR), 0.9) } returns emptyList()
        every { allocationRepository.save(any()) } answers { firstArg() }

        // when
        processor.processAllocation(pending)

        // then
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
        verify(exactly = 0) { equipmentRepository.findByIdsForUpdate(any(), any()) }
        verify {
            allocationRepository.save(match {
                it.id == allocationId &&
                    it.state == AllocationState.FAILED &&
                    it.allocatedEquipmentIds.isEmpty()
            })
        }
    }

    @Test
    fun `processAllocation should throw AllocationLockContentionException when all candidates are locked by concurrent requests`() {
        // given
        val allocationId = UUID.randomUUID()
        val candidateEquipment = equipment(type = EquipmentType.MONITOR, conditionScore = 0.92)
        val pending = allocation(
            id = allocationId,
            state = AllocationState.PENDING,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, minimumConditionScore = 0.8))
        )
        every { allocationRepository.findById(allocationId) } returns pending
        every { equipmentRepository.findAvailableWithMinConditionScore(setOf(EquipmentType.MONITOR), 0.8) } returns listOf(candidateEquipment)
        every { equipmentRepository.findByIdsForUpdate(listOf(candidateEquipment.id), 0.8) } returns emptyList()

        // when / then
        assertThrows(com.tequipy.challenge.domain.AllocationLockContentionException::class.java) {
            processor.processAllocation(pending)
        }
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
        verify(exactly = 0) { allocationRepository.save(any()) }
    }

    @Test
    fun `processAllocation should throw AllocationLockContentionException when some candidates are locked by concurrent requests`() {
        // given
        val allocationId = UUID.randomUUID()
        val candidate1 = equipment(type = EquipmentType.MONITOR, conditionScore = 0.92)
        val candidate2 = equipment(type = EquipmentType.MONITOR, conditionScore = 0.88)
        val pending = allocation(
            id = allocationId,
            state = AllocationState.PENDING,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 2, minimumConditionScore = 0.8))
        )
        every { allocationRepository.findById(allocationId) } returns pending
        every { equipmentRepository.findAvailableWithMinConditionScore(setOf(EquipmentType.MONITOR), 0.8) } returns listOf(candidate1, candidate2)
        // Only one of the two candidates could be locked — partial contention
        every { equipmentRepository.findByIdsForUpdate(any(), 0.8) } returns listOf(candidate1)

        // when / then
        assertThrows(com.tequipy.challenge.domain.AllocationLockContentionException::class.java) {
            processor.processAllocation(pending)
        }
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
        verify(exactly = 0) { allocationRepository.save(any()) }
    }

    @Test
    fun `processAllocation should reserve selected equipment and mark allocation allocated`() {
        // given
        val allocationId = UUID.randomUUID()
        val selectedEquipment = equipment(type = EquipmentType.MONITOR, conditionScore = 0.92)
        val pending = allocation(
            id = allocationId,
            state = AllocationState.PENDING,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, minimumConditionScore = 0.8))
        )
        every { allocationRepository.findById(allocationId) } returns pending
        every { equipmentRepository.findAvailableWithMinConditionScore(setOf(EquipmentType.MONITOR), 0.8) } returns listOf(selectedEquipment)
        every { equipmentRepository.findByIdsForUpdate(listOf(selectedEquipment.id), 0.8) } returns listOf(selectedEquipment)
        every { equipmentRepository.saveAll(any()) } answers { firstArg() }
        every { allocationRepository.save(any()) } answers { firstArg() }

        // when
        processor.processAllocation(pending)

        // then
        verify {
            equipmentRepository.saveAll(match { saved ->
                saved.size == 1 && saved.single().id == selectedEquipment.id && saved.single().state == EquipmentState.RESERVED
            })
        }
        verify {
            allocationRepository.save(match {
                it.id == allocationId &&
                    it.state == AllocationState.ALLOCATED &&
                    it.allocatedEquipmentIds == listOf(selectedEquipment.id)
            })
        }
    }

    private fun allocation(
        id: UUID = UUID.randomUUID(),
        state: AllocationState,
        policy: List<EquipmentPolicyRequirement>,
        allocatedEquipmentIds: List<UUID> = emptyList()
    ) = AllocationRequest(
        id = id,
        policy = policy,
        state = state,
        allocatedEquipmentIds = allocatedEquipmentIds
    )

    private fun equipment(
        id: UUID = UUID.randomUUID(),
        type: EquipmentType,
        conditionScore: Double,
        state: EquipmentState = EquipmentState.AVAILABLE
    ) = Equipment(
        id = id,
        type = type,
        brand = "Dell",
        model = "Model",
        state = state,
        conditionScore = conditionScore,
        purchaseDate = LocalDate.of(2024, 1, 1),
        retiredReason = null
    )
}

