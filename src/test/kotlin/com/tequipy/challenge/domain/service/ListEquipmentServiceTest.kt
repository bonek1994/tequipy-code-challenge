package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ListEquipmentServiceTest {
    private val equipmentRepository: EquipmentRepository = mockk()
    private val service = ListEquipmentService(equipmentRepository)

    @Test
    fun `listEquipment should return all equipment when state filter is null`() {
        val equipmentList = listOf(
            sampleEquipment(type = EquipmentType.MAIN_COMPUTER),
            sampleEquipment(type = EquipmentType.MOUSE)
        )
        every { equipmentRepository.findAll() } returns equipmentList

        val result = service.listEquipment(null)

        assertEquals(2, result.size)
        assertEquals(equipmentList, result)
    }

    @Test
    fun `listEquipment should filter by state when provided`() {
        val equipmentList = listOf(sampleEquipment(state = EquipmentState.RETIRED, retiredReason = "Damaged"))
        every { equipmentRepository.findByState(EquipmentState.RETIRED) } returns equipmentList

        val result = service.listEquipment(EquipmentState.RETIRED)

        assertEquals(equipmentList, result)
    }

    private fun sampleEquipment(
        id: UUID = UUID.randomUUID(),
        type: EquipmentType = EquipmentType.MONITOR,
        state: EquipmentState = EquipmentState.AVAILABLE,
        retiredReason: String? = null
    ) = Equipment(
        id = id,
        type = type,
        brand = "Dell",
        model = "U2723QE",
        state = state,
        conditionScore = 0.9,
        purchaseDate = LocalDate.of(2024, 6, 1),
        retiredReason = retiredReason
    )
}

