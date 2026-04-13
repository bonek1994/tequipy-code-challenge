package com.tequipy.challenge.adapter.api.web.mapper

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class EquipmentMapperTest {

    private val mapper = EquipmentMapper()

    @Test
    fun `toResponse maps all equipment fields`() {
        val id = UUID.randomUUID()
        val equipment = Equipment(
            id = id,
            type = EquipmentType.MAIN_COMPUTER,
            brand = "Apple",
            model = "MacBook Pro 16",
            state = EquipmentState.AVAILABLE,
            conditionScore = 0.95,
            purchaseDate = LocalDate.of(2024, 3, 15),
            retiredReason = null
        )

        val response = mapper.toResponse(equipment)

        assertEquals(id, response.id)
        assertEquals(EquipmentType.MAIN_COMPUTER, response.type)
        assertEquals("Apple", response.brand)
        assertEquals("MacBook Pro 16", response.model)
        assertEquals(EquipmentState.AVAILABLE, response.state)
        assertEquals(0.95, response.conditionScore)
        assertEquals(LocalDate.of(2024, 3, 15), response.purchaseDate)
        assertNull(response.retiredReason)
    }

    @Test
    fun `toResponse maps retired equipment with reason`() {
        val id = UUID.randomUUID()
        val equipment = Equipment(
            id = id,
            type = EquipmentType.MONITOR,
            brand = "Dell",
            model = "U2723QE",
            state = EquipmentState.RETIRED,
            conditionScore = 0.3,
            purchaseDate = LocalDate.of(2022, 6, 1),
            retiredReason = "Screen damage"
        )

        val response = mapper.toResponse(equipment)

        assertEquals(EquipmentState.RETIRED, response.state)
        assertEquals("Screen damage", response.retiredReason)
    }

    @Test
    fun `toResponseList maps multiple equipment items`() {
        val equipment1 = Equipment(
            id = UUID.randomUUID(),
            type = EquipmentType.KEYBOARD,
            brand = "Logitech",
            model = "MX Keys",
            state = EquipmentState.AVAILABLE,
            conditionScore = 0.9,
            purchaseDate = LocalDate.of(2024, 1, 1)
        )
        val equipment2 = Equipment(
            id = UUID.randomUUID(),
            type = EquipmentType.MOUSE,
            brand = "Logitech",
            model = "MX Master 3",
            state = EquipmentState.ASSIGNED,
            conditionScore = 0.85,
            purchaseDate = LocalDate.of(2023, 11, 20)
        )

        val responses = mapper.toResponseList(listOf(equipment1, equipment2))

        assertEquals(2, responses.size)
        assertEquals(equipment1.id, responses[0].id)
        assertEquals(equipment2.id, responses[1].id)
    }

    @Test
    fun `toResponseList returns empty list for empty input`() {
        val responses = mapper.toResponseList(emptyList())
        assertEquals(emptyList<Any>(), responses)
    }
}

