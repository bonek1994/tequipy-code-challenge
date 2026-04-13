package com.tequipy.challenge.adapter.spi.persistence.mapper

import com.tequipy.challenge.adapter.spi.persistence.entity.EquipmentEntity
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class EquipmentEntityMapperTest {

    private val mapper = EquipmentEntityMapper()

    @Test
    fun `toDomain maps all fields from persistence entity to domain model`() {
        val id = UUID.randomUUID()
        val entity = EquipmentEntity(
            id = id,
            type = EquipmentType.MAIN_COMPUTER,
            brand = "Apple",
            model = "MacBook Pro 16",
            state = EquipmentState.AVAILABLE,
            conditionScore = 0.95,
            purchaseDate = LocalDate.of(2024, 3, 15),
            retiredReason = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val domain = mapper.toDomain(entity)

        assertEquals(id, domain.id)
        assertEquals(EquipmentType.MAIN_COMPUTER, domain.type)
        assertEquals("Apple", domain.brand)
        assertEquals("MacBook Pro 16", domain.model)
        assertEquals(EquipmentState.AVAILABLE, domain.state)
        assertEquals(0.95, domain.conditionScore)
        assertEquals(LocalDate.of(2024, 3, 15), domain.purchaseDate)
        assertNull(domain.retiredReason)
    }

    @Test
    fun `toDomain maps retired entity with reason`() {
        val entity = EquipmentEntity(
            id = UUID.randomUUID(),
            type = EquipmentType.MONITOR,
            brand = "Dell",
            model = "U2723QE",
            state = EquipmentState.RETIRED,
            conditionScore = 0.3,
            purchaseDate = LocalDate.of(2022, 6, 1),
            retiredReason = "Screen damage"
        )

        val domain = mapper.toDomain(entity)

        assertEquals(EquipmentState.RETIRED, domain.state)
        assertEquals("Screen damage", domain.retiredReason)
    }

    @Test
    fun `toEntity maps all fields from domain model to persistence entity`() {
        val id = UUID.randomUUID()
        val domain = Equipment(
            id = id,
            type = EquipmentType.KEYBOARD,
            brand = "Logitech",
            model = "MX Keys",
            state = EquipmentState.ASSIGNED,
            conditionScore = 0.88,
            purchaseDate = LocalDate.of(2023, 12, 1),
            retiredReason = null
        )

        val entity = mapper.toEntity(domain)

        assertEquals(id, entity.id)
        assertEquals(EquipmentType.KEYBOARD, entity.type)
        assertEquals("Logitech", entity.brand)
        assertEquals("MX Keys", entity.model)
        assertEquals(EquipmentState.ASSIGNED, entity.state)
        assertEquals(0.88, entity.conditionScore)
        assertEquals(LocalDate.of(2023, 12, 1), entity.purchaseDate)
        assertNull(entity.retiredReason)
    }

    @Test
    fun `roundtrip domain to entity and back preserves data`() {
        val original = Equipment(
            id = UUID.randomUUID(),
            type = EquipmentType.MOUSE,
            brand = "Razer",
            model = "DeathAdder V3",
            state = EquipmentState.RESERVED,
            conditionScore = 1.0,
            purchaseDate = LocalDate.of(2025, 1, 10),
            retiredReason = null
        )

        val roundtripped = mapper.toDomain(mapper.toEntity(original))

        assertEquals(original, roundtripped)
    }
}

