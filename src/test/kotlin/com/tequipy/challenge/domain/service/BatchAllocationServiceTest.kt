package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.command.ProcessAllocationCommand
import com.tequipy.challenge.domain.model.AllocationProcessingRecord
import com.tequipy.challenge.domain.model.AllocationProcessingState
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.spi.AllocationEventPublisher
import com.tequipy.challenge.domain.port.spi.AllocationProcessingRepository
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class BatchAllocationServiceTest {

    private val equipmentRepository: EquipmentRepository = mockk(relaxed = true)
    private val allocationProcessingRepository: AllocationProcessingRepository = mockk(relaxed = true)
    private val allocationEventPublisher: AllocationEventPublisher = mockk(relaxed = true)
    private val metrics = BatchAllocationMetrics()
    private val service = BatchAllocationService(equipmentRepository, allocationProcessingRepository, allocationEventPublisher, metrics)

    // -------------------------------------------------------------------------
    // Empty / trivial cases
    // -------------------------------------------------------------------------

    @Test
    fun `processBatch with empty list does nothing`() {
        service.processBatch(emptyList())
        verify(exactly = 0) { equipmentRepository.findAvailableWithMinConditionScore(any(), any()) }
        verify(exactly = 0) { allocationEventPublisher.publishAllocationProcessed(any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Success path
    // -------------------------------------------------------------------------

    @Test
    fun `processBatch reserves equipment and publishes success for each command`() {
        val cmd1 = command(policy = listOf(req(EquipmentType.MONITOR, 1)))
        val cmd2 = command(policy = listOf(req(EquipmentType.MONITOR, 1)))
        val eq1  = equipment(EquipmentType.MONITOR, conditionScore = 0.9)
        val eq2  = equipment(EquipmentType.MONITOR, conditionScore = 0.85)

        every { allocationProcessingRepository.tryStart(any()) } returns true
        every { equipmentRepository.findAvailableWithMinConditionScore(any(), any()) } returns listOf(eq1, eq2)
        every { equipmentRepository.findByIdsForUpdate(any(), any()) } returns listOf(eq1, eq2)
        every { equipmentRepository.updateAll(any()) } answers { firstArg() }
        every { allocationProcessingRepository.complete(any(), any(), any()) } answers {
            AllocationProcessingRecord(firstArg(), secondArg(), thirdArg())
        }

        service.processBatch(listOf(cmd1, cmd2))

        // Both equipment items must be reserved
        verify {
            equipmentRepository.updateAll(match { saved ->
                saved.size == 2 && saved.all { it.state == EquipmentState.RESERVED }
            })
        }
        // Both allocations succeed
        verify { allocationEventPublisher.publishAllocationProcessed(cmd1.allocationId, true, any()) }
        verify { allocationEventPublisher.publishAllocationProcessed(cmd2.allocationId, true, any()) }
    }

    @Test
    fun `processBatch marks allocation FAILED when no equipment matches policy`() {
        val cmd = command(policy = listOf(req(EquipmentType.MONITOR, 1, minimumConditionScore = 0.95)))

        every { allocationProcessingRepository.tryStart(any()) } returns true
        every { equipmentRepository.findAvailableWithMinConditionScore(any(), any()) } returns emptyList()
        every { allocationProcessingRepository.complete(any(), any(), any()) } answers {
            AllocationProcessingRecord(firstArg(), secondArg(), thirdArg())
        }

        service.processBatch(listOf(cmd))

        verify(exactly = 0) { equipmentRepository.updateAll(any()) }
        verify { allocationProcessingRepository.complete(cmd.allocationId, AllocationProcessingState.FAILED, emptyList()) }
        verify { allocationEventPublisher.publishAllocationProcessed(cmd.allocationId, false, emptyList()) }
    }

    // -------------------------------------------------------------------------
    // No double-assignment within a batch
    // -------------------------------------------------------------------------

    @Test
    fun `processBatch does not assign the same equipment item to two requests`() {
        val cmd1 = command(policy = listOf(req(EquipmentType.MONITOR, 1)))
        val cmd2 = command(policy = listOf(req(EquipmentType.MONITOR, 1)))
        // Only ONE monitor available — second request must fail
        val onlyMonitor = equipment(EquipmentType.MONITOR, conditionScore = 0.9)

        every { allocationProcessingRepository.tryStart(any()) } returns true
        every { equipmentRepository.findAvailableWithMinConditionScore(any(), any()) } returns listOf(onlyMonitor)
        every { equipmentRepository.findByIdsForUpdate(any(), any()) } returns listOf(onlyMonitor)
        every { equipmentRepository.updateAll(any()) } answers { firstArg() }
        every { allocationProcessingRepository.complete(any(), any(), any()) } answers {
            AllocationProcessingRecord(firstArg(), secondArg(), thirdArg())
        }

        service.processBatch(listOf(cmd1, cmd2))

        // Exactly one equipment item reserved
        verify {
            equipmentRepository.updateAll(match { saved -> saved.size == 1 })
        }
        // One success, one failure (order depends on which command is processed first)
        verify(exactly = 1) { allocationEventPublisher.publishAllocationProcessed(any(), true, any()) }
        verify(exactly = 1) { allocationEventPublisher.publishAllocationProcessed(any(), false, emptyList()) }
    }

    // -------------------------------------------------------------------------
    // Most-constrained-first ordering
    // -------------------------------------------------------------------------

    @Test
    fun `processBatch processes most constrained request first when pool is limited`() {
        // Two commands; cmd_strict needs score >= 0.9, cmd_loose just needs >= 0.5.
        // Only one equipment available with score 0.92 (satisfies both).
        // Most-constrained-first means cmd_strict wins; cmd_loose fails.
        val cmdStrict = command(policy = listOf(req(EquipmentType.MONITOR, 1, minimumConditionScore = 0.9)))
        val cmdLoose  = command(policy = listOf(req(EquipmentType.MONITOR, 1, minimumConditionScore = 0.5)))
        val highScore = equipment(EquipmentType.MONITOR, conditionScore = 0.92)

        every { allocationProcessingRepository.tryStart(any()) } returns true
        every { equipmentRepository.findAvailableWithMinConditionScore(any(), any()) } returns listOf(highScore)
        every { equipmentRepository.findByIdsForUpdate(any(), any()) } returns listOf(highScore)
        every { equipmentRepository.updateAll(any()) } answers { firstArg() }
        every { allocationProcessingRepository.complete(any(), any(), any()) } answers {
            AllocationProcessingRecord(firstArg(), secondArg(), thirdArg())
        }

        service.processBatch(listOf(cmdLoose, cmdStrict)) // deliberately submit loose first

        verify { allocationEventPublisher.publishAllocationProcessed(cmdStrict.allocationId, true, any()) }
        verify { allocationEventPublisher.publishAllocationProcessed(cmdLoose.allocationId, false, emptyList()) }
    }

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    @Test
    fun `processBatch republishes cached result for ALLOCATED duplicate without re-running algorithm`() {
        val cmd = command(policy = listOf(req(EquipmentType.MONITOR, 1)))
        val cachedId = UUID.randomUUID()
        val cached = AllocationProcessingRecord(cmd.allocationId, AllocationProcessingState.ALLOCATED, listOf(cachedId))

        every { allocationProcessingRepository.tryStart(cmd.allocationId) } returns false
        every { allocationProcessingRepository.findById(cmd.allocationId) } returns cached

        service.processBatch(listOf(cmd))

        verify(exactly = 0) { equipmentRepository.findAvailableWithMinConditionScore(any(), any()) }
        verify { allocationEventPublisher.publishAllocationProcessed(cmd.allocationId, true, listOf(cachedId)) }
    }

    @Test
    fun `processBatch republishes cached FAILED result without re-running algorithm`() {
        val cmd = command(policy = listOf(req(EquipmentType.MONITOR, 1)))
        val cached = AllocationProcessingRecord(cmd.allocationId, AllocationProcessingState.FAILED)

        every { allocationProcessingRepository.tryStart(cmd.allocationId) } returns false
        every { allocationProcessingRepository.findById(cmd.allocationId) } returns cached

        service.processBatch(listOf(cmd))

        verify(exactly = 0) { equipmentRepository.findAvailableWithMinConditionScore(any(), any()) }
        verify { allocationEventPublisher.publishAllocationProcessed(cmd.allocationId, false, emptyList()) }
    }

    // -------------------------------------------------------------------------
    // Mixed batch
    // -------------------------------------------------------------------------

    @Test
    fun `processBatch handles mix of successful and failed allocations`() {
        val cmdSuccess = command(policy = listOf(req(EquipmentType.MONITOR, 1)))
        val cmdFail    = command(policy = listOf(req(EquipmentType.KEYBOARD, 1))) // no keyboard available
        val monitor    = equipment(EquipmentType.MONITOR, conditionScore = 0.9)

        every { allocationProcessingRepository.tryStart(any()) } returns true
        every {
            equipmentRepository.findAvailableWithMinConditionScore(any(), any())
        } returns listOf(monitor)
        every { equipmentRepository.findByIdsForUpdate(any(), any()) } returns listOf(monitor)
        every { equipmentRepository.updateAll(any()) } answers { firstArg() }
        every { allocationProcessingRepository.complete(any(), any(), any()) } answers {
            AllocationProcessingRecord(firstArg(), secondArg(), thirdArg())
        }

        service.processBatch(listOf(cmdSuccess, cmdFail))

        verify { allocationEventPublisher.publishAllocationProcessed(cmdSuccess.allocationId, true, any()) }
        verify { allocationEventPublisher.publishAllocationProcessed(cmdFail.allocationId, false, emptyList()) }
    }

    @Test
    fun `processBatch performs single read and lock across the whole batch`() {
        val cmd1 = command(policy = listOf(req(EquipmentType.MONITOR, 1)))
        val cmd2 = command(policy = listOf(req(EquipmentType.MONITOR, 1)))
        val eq1  = equipment(EquipmentType.MONITOR, conditionScore = 0.9)
        val eq2  = equipment(EquipmentType.MONITOR, conditionScore = 0.85)

        every { allocationProcessingRepository.tryStart(any()) } returns true
        every { equipmentRepository.findAvailableWithMinConditionScore(any(), any()) } returns listOf(eq1, eq2)
        every { equipmentRepository.findByIdsForUpdate(any(), any()) } returns listOf(eq1, eq2)
        every { equipmentRepository.updateAll(any()) } answers { firstArg() }
        every { allocationProcessingRepository.complete(any(), any(), any()) } answers {
            AllocationProcessingRecord(firstArg(), secondArg(), thirdArg())
        }

        service.processBatch(listOf(cmd1, cmd2))

        // Only ONE findAvailableWithMinConditionScore call for the entire batch
        verify(exactly = 1) { equipmentRepository.findAvailableWithMinConditionScore(any(), any()) }
        // Only ONE findByIdsForUpdate call for the entire batch
        verify(exactly = 1) { equipmentRepository.findByIdsForUpdate(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun command(policy: List<EquipmentPolicyRequirement>) =
        ProcessAllocationCommand(allocationId = UUID.randomUUID(), policy = policy)

    private fun req(
        type: EquipmentType,
        quantity: Int,
        minimumConditionScore: Double? = null,
        preferredBrand: String? = null
    ) = EquipmentPolicyRequirement(type = type, quantity = quantity, minimumConditionScore = minimumConditionScore, preferredBrand = preferredBrand)

    private fun equipment(
        type: EquipmentType,
        conditionScore: Double,
        state: EquipmentState = EquipmentState.AVAILABLE
    ) = Equipment(
        id = UUID.randomUUID(),
        type = type,
        brand = "Dell",
        model = "Model X",
        state = state,
        conditionScore = conditionScore,
        purchaseDate = LocalDate.of(2024, 1, 1)
    )
}
