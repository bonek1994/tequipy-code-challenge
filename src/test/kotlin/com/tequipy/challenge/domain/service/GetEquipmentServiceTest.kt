package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class GetEquipmentServiceTest {
    private val equipmentRepository: EquipmentRepository = mockk()
    private val service = GetEquipmentService(equipmentRepository)

    @Test
    fun `getEquipment should return equipment when found`() {
        val id = UUID.randomUUID()
        val equipment = sampleEquipment(id = id)
        every { equipmentRepository.findById(id) } returns equipment

        val result = service.getEquipment(id)

        assertEquals(equipment, result)
    }

    @Test
    fun `getEquipment should throw NotFoundException when not found`() {
        val id = UUID.randomUUID()
        every { equipmentRepository.findById(id) } returns null

        assertThrows(NotFoundException::class.java) {
            service.getEquipment(id)
        }
    }

    private fun sampleEquipment(id: UUID) = Equipment(
        id = id,
        type = EquipmentType.MONITOR,
        brand = "Dell",
        model = "U2723QE",
        state = EquipmentState.AVAILABLE,
        conditionScore = 0.9,
        purchaseDate = LocalDate.of(2024, 6, 1),
        retiredReason = null
    )
}

