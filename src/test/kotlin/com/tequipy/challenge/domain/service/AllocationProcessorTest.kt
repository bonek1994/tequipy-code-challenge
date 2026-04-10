package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.out.AllocationRepository
import com.tequipy.challenge.domain.port.out.EquipmentRepository
import io.mockk.*
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
        every { allocationRepository.findById(allocationId) } returns null

        // when
        processor.processAllocation(allocationId)

        // then
        verify(exactly = 0) { equipmentRepository.findByState(any()) }
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
        verify(exactly = 0) { allocationRepository.save(any()) }
    }

    @Test
    fun `processAllocation should ignore allocation that is not pending`() {
        // given
        val allocationId = UUID.randomUUID()
        every { allocationRepository.findById(allocationId) } returns allocation(
            id = allocationId,
            state = AllocationState.ALLOCATED,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1))
        )

        // when
        processor.processAllocation(allocationId)

        // then
        verify(exactly = 0) { equipmentRepository.findByState(any()) }
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
        val unavailableCandidate = equipment(type = EquipmentType.MONITOR, conditionScore = 0.5)
        every { allocationRepository.findById(allocationId) } returns pending
        every { equipmentRepository.findByState(EquipmentState.AVAILABLE) } returns listOf(unavailableCandidate)
        every { allocationRepository.save(any()) } answers { firstArg() }

        // when
        processor.processAllocation(allocationId)

        // then
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
        verify(exactly = 0) { equipmentRepository.findByIdsForUpdate(any()) }
        verify {
            allocationRepository.save(match {
                it.id == allocationId &&
                    it.state == AllocationState.FAILED &&
                    it.allocatedEquipmentIds.isEmpty()
            })
        }
    }

    @Test
    fun `processAllocation should retry and fail after max retries when all candidates are persistently locked`() {
        // given
        val allocationId = UUID.randomUUID()
        val candidateEquipment = equipment(type = EquipmentType.MONITOR, conditionScore = 0.92)
        val pending = allocation(
            id = allocationId,
            state = AllocationState.PENDING,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, minimumConditionScore = 0.8))
        )
        every { allocationRepository.findById(allocationId) } returns pending
        // Candidate is visible as AVAILABLE on every attempt but always skip-locked
        every { equipmentRepository.findByState(EquipmentState.AVAILABLE) } returns listOf(candidateEquipment)
        every { equipmentRepository.findByIdsForUpdate(listOf(candidateEquipment.id)) } returns emptyList()
        every { allocationRepository.save(any()) } answers { firstArg() }

        // when
        processor.processAllocation(allocationId)

        // then
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
        // Phase 1+2 should have been attempted MAX_RETRIES times
        verify(exactly = AllocationProcessor.MAX_RETRIES) { equipmentRepository.findByState(EquipmentState.AVAILABLE) }
        verify(exactly = AllocationProcessor.MAX_RETRIES) { equipmentRepository.findByIdsForUpdate(any()) }
        // Allocation is eventually marked FAILED after all retries are exhausted
        verify(exactly = 1) {
            allocationRepository.save(match {
                it.id == allocationId &&
                    it.state == AllocationState.FAILED &&
                    it.allocatedEquipmentIds.isEmpty()
            })
        }
    }

    @Test
    fun `processAllocation should succeed on retry when contention resolves`() {
        // given
        val allocationId = UUID.randomUUID()
        val candidateEquipment = equipment(type = EquipmentType.MONITOR, conditionScore = 0.92)
        val pending = allocation(
            id = allocationId,
            state = AllocationState.PENDING,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, minimumConditionScore = 0.8))
        )
        every { allocationRepository.findById(allocationId) } returns pending
        every { equipmentRepository.findByState(EquipmentState.AVAILABLE) } returns listOf(candidateEquipment)
        // First attempt: candidate is skip-locked; second attempt: candidate is available
        every { equipmentRepository.findByIdsForUpdate(listOf(candidateEquipment.id)) } returnsMany
            listOf(emptyList(), listOf(candidateEquipment))
        every { equipmentRepository.saveAll(any()) } answers { firstArg() }
        every { allocationRepository.save(any()) } answers { firstArg() }

        // when
        processor.processAllocation(allocationId)

        // then – allocation succeeds on the second attempt, never marked FAILED
        verify(exactly = 2) { equipmentRepository.findByIdsForUpdate(any()) }
        verify {
            equipmentRepository.saveAll(match { saved ->
                saved.size == 1 && saved.single().id == candidateEquipment.id && saved.single().state == EquipmentState.RESERVED
            })
        }
        verify {
            allocationRepository.save(match {
                it.id == allocationId &&
                    it.state == AllocationState.ALLOCATED &&
                    it.allocatedEquipmentIds == listOf(candidateEquipment.id)
            })
        }
        verify(exactly = 0) {
            allocationRepository.save(match { it.state == AllocationState.FAILED })
        }
    }

    @Test
    fun `processAllocation should fail immediately without retrying when algorithm fails with no contention`() {
        // given – only one monitor available, but algorithm needs two (genuine shortage)
        val allocationId = UUID.randomUUID()
        val singleMonitor = equipment(type = EquipmentType.MONITOR, conditionScore = 0.92)
        val pending = allocation(
            id = allocationId,
            state = AllocationState.PENDING,
            policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 2))
        )
        every { allocationRepository.findById(allocationId) } returns pending
        every { equipmentRepository.findByState(EquipmentState.AVAILABLE) } returns listOf(singleMonitor)
        // All candidates are successfully locked (no contention) – there is just not enough
        every { equipmentRepository.findByIdsForUpdate(listOf(singleMonitor.id)) } returns listOf(singleMonitor)
        every { allocationRepository.save(any()) } answers { firstArg() }

        // when
        processor.processAllocation(allocationId)

        // then – fails on first attempt without any retries
        verify(exactly = 1) { equipmentRepository.findByState(EquipmentState.AVAILABLE) }
        verify(exactly = 1) { equipmentRepository.findByIdsForUpdate(any()) }
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
        verify(exactly = 1) {
            allocationRepository.save(match {
                it.id == allocationId &&
                    it.state == AllocationState.FAILED &&
                    it.allocatedEquipmentIds.isEmpty()
            })
        }
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
        every { equipmentRepository.findByState(EquipmentState.AVAILABLE) } returns listOf(selectedEquipment)
        every { equipmentRepository.findByIdsForUpdate(listOf(selectedEquipment.id)) } returns listOf(selectedEquipment)
        every { equipmentRepository.saveAll(any()) } answers { firstArg() }
        every { allocationRepository.save(any()) } answers { firstArg() }

        // when
        processor.processAllocation(allocationId)

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
        employeeId = UUID.randomUUID(),
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

