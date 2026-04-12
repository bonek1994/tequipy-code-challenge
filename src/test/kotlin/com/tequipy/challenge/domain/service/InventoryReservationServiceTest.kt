package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.AllocationLockContentionException
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class InventoryReservationServiceTest {

    private val equipmentRepository: EquipmentRepository = mockk(relaxed = true)
    private val service = InventoryReservationService(equipmentRepository)

    @Test
    fun `reserveForAllocation should return null when no candidate equipment exists`() {
        val allocationId = UUID.randomUUID()
        val policy = listOf(
            EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1, minimumConditionScore = 0.9)
        )

        every { equipmentRepository.findAvailableWithMinConditionScore(setOf(EquipmentType.MONITOR), 0.9) } returns emptyList()

        val result = service.reserveForAllocation(allocationId, policy)

        assertNull(result)
        verify(exactly = 0) { equipmentRepository.findByIdsForUpdate(any(), any()) }
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
    }

    @Test
    fun `reserveForAllocation should throw when not all candidate locks are acquired`() {
        val allocationId = UUID.randomUUID()
        val candidate = equipment(type = EquipmentType.MONITOR, conditionScore = 0.92)
        val policy = listOf(
            EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1, minimumConditionScore = 0.8)
        )

        every { equipmentRepository.findAvailableWithMinConditionScore(setOf(EquipmentType.MONITOR), 0.8) } returns listOf(candidate)
        every { equipmentRepository.findByIdsForUpdate(listOf(candidate.id), 0.8) } returns emptyList()

        assertThrows(AllocationLockContentionException::class.java) {
            service.reserveForAllocation(allocationId, policy)
        }
        verify(exactly = 0) { equipmentRepository.saveAll(any()) }
    }

    @Test
    fun `reserveForAllocation should reserve selected equipment and return ids`() {
        val allocationId = UUID.randomUUID()
        val selectedEquipment = equipment(type = EquipmentType.MONITOR, conditionScore = 0.92)
        val policy = listOf(
            EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1, minimumConditionScore = 0.8)
        )

        every { equipmentRepository.findAvailableWithMinConditionScore(setOf(EquipmentType.MONITOR), 0.8) } returns listOf(selectedEquipment)
        every { equipmentRepository.findByIdsForUpdate(listOf(selectedEquipment.id), 0.8) } returns listOf(selectedEquipment)
        every { equipmentRepository.saveAll(any()) } answers { firstArg() }

        val result = service.reserveForAllocation(allocationId, policy)

        assertEquals(listOf(selectedEquipment.id), result)
        verify {
            equipmentRepository.saveAll(match { saved ->
                saved.size == 1 && saved.single().id == selectedEquipment.id && saved.single().state == EquipmentState.RESERVED
            })
        }
    }

    @Test
    fun `confirmReservedEquipment should move all found equipment to assigned`() {
        val equipmentId = UUID.randomUUID()
        val equipment = equipment(id = equipmentId, type = EquipmentType.MONITOR, conditionScore = 0.92, state = EquipmentState.RESERVED)
        every { equipmentRepository.findByIds(listOf(equipmentId)) } returns listOf(equipment)
        every { equipmentRepository.saveAll(any()) } answers { firstArg() }

        service.confirmReservedEquipment(listOf(equipmentId))

        verify {
            equipmentRepository.saveAll(match { saved ->
                saved.size == 1 && saved.single().id == equipmentId && saved.single().state == EquipmentState.ASSIGNED
            })
        }
    }

    @Test
    fun `releaseReservedEquipment should only move reserved equipment back to available`() {
        val reserved = equipment(type = EquipmentType.MONITOR, conditionScore = 0.92, state = EquipmentState.RESERVED)
        val assigned = equipment(type = EquipmentType.MONITOR, conditionScore = 0.88, state = EquipmentState.ASSIGNED)
        every { equipmentRepository.findByIds(listOf(reserved.id, assigned.id)) } returns listOf(reserved, assigned)
        every { equipmentRepository.saveAll(any()) } answers { firstArg() }

        service.releaseReservedEquipment(listOf(reserved.id, assigned.id))

        verify {
            equipmentRepository.saveAll(match { saved ->
                saved.size == 2 &&
                    saved.any { it.id == reserved.id && it.state == EquipmentState.AVAILABLE } &&
                    saved.any { it.id == assigned.id && it.state == EquipmentState.ASSIGNED }
            })
        }
    }

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

