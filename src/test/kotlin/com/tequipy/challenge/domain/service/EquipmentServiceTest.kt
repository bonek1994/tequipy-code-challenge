package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.out.EquipmentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

class EquipmentServiceTest {

    private val equipmentRepository: EquipmentRepository = mockk()
    private val equipmentService = EquipmentService(equipmentRepository)

    @Test
    fun `registerEquipment should save available equipment`() {
        val purchaseDate = LocalDate.of(2025, 1, 10)
        val saved = sampleEquipment(state = EquipmentState.AVAILABLE, purchaseDate = purchaseDate)

        every { equipmentRepository.save(any()) } returns saved

        val result = equipmentService.registerEquipment(
            type = EquipmentType.MAIN_COMPUTER,
            brand = "Apple",
            model = "MacBook Pro",
            conditionScore = 0.92,
            purchaseDate = purchaseDate
        )

        assertEquals(saved, result)
        verify { equipmentRepository.save(withArg { assertEquals(EquipmentState.AVAILABLE, it.state) }) }
    }

    @Test
    fun `registerEquipment should reject invalid condition score`() {
        assertThrows<BadRequestException> {
            equipmentService.registerEquipment(
                type = EquipmentType.MONITOR,
                brand = "Dell",
                model = "U2723QE",
                conditionScore = 1.5,
                purchaseDate = LocalDate.now()
            )
        }
    }

    @Test
    fun `registerEquipment should allow boundary condition scores`() {
        every { equipmentRepository.save(any()) } answers { firstArg() }

        val minResult = equipmentService.registerEquipment(
            type = EquipmentType.KEYBOARD,
            brand = "Logitech",
            model = "MX Keys",
            conditionScore = 0.0,
            purchaseDate = LocalDate.of(2024, 5, 1)
        )
        val maxResult = equipmentService.registerEquipment(
            type = EquipmentType.MOUSE,
            brand = "Logitech",
            model = "MX Master 3S",
            conditionScore = 1.0,
            purchaseDate = LocalDate.of(2024, 5, 1)
        )

        assertEquals(0.0, minResult.conditionScore)
        assertEquals(1.0, maxResult.conditionScore)
        verify(exactly = 2) { equipmentRepository.save(any()) }
    }

    @Test
    fun `registerEquipment should reject blank brand or model`() {
        assertAll(
            {
                assertThrows<BadRequestException> {
                    equipmentService.registerEquipment(
                        type = EquipmentType.MONITOR,
                        brand = "   ",
                        model = "U2723QE",
                        conditionScore = 0.8,
                        purchaseDate = LocalDate.now()
                    )
                }
            },
            {
                assertThrows<BadRequestException> {
                    equipmentService.registerEquipment(
                        type = EquipmentType.MONITOR,
                        brand = "Dell",
                        model = "   ",
                        conditionScore = 0.8,
                        purchaseDate = LocalDate.now()
                    )
                }
            }
        )
    }

    @Test
    fun `getEquipment should return equipment when found`() {
        val id = UUID.randomUUID()
        val equipment = sampleEquipment(id = id)

        every { equipmentRepository.findById(id) } returns equipment

        val result = equipmentService.getEquipment(id)

        assertEquals(equipment, result)
    }

    @Test
    fun `getEquipment should throw NotFoundException when not found`() {
        val id = UUID.randomUUID()

        every { equipmentRepository.findById(id) } returns null

        assertThrows<NotFoundException> {
            equipmentService.getEquipment(id)
        }
    }

    @Test
    fun `listEquipment should return all equipment when state filter is null`() {
        val equipmentList = listOf(
            sampleEquipment(type = EquipmentType.MAIN_COMPUTER),
            sampleEquipment(type = EquipmentType.MOUSE)
        )

        every { equipmentRepository.findAll() } returns equipmentList

        val result = equipmentService.listEquipment(null)

        assertEquals(2, result.size)
        assertEquals(equipmentList, result)
    }

    @Test
    fun `listEquipment should filter by state when provided`() {
        val equipmentList = listOf(sampleEquipment(state = EquipmentState.RETIRED, retiredReason = "Damaged"))

        every { equipmentRepository.findByState(EquipmentState.RETIRED) } returns equipmentList

        val result = equipmentService.listEquipment(EquipmentState.RETIRED)

        assertEquals(equipmentList, result)
    }

    @Test
    fun `retireEquipment should set retired state with reason`() {
        val id = UUID.randomUUID()
        val existing = sampleEquipment(id = id, state = EquipmentState.AVAILABLE)
        val retired = existing.copy(state = EquipmentState.RETIRED, retiredReason = "Broken hinge")

        every { equipmentRepository.findById(id) } returns existing
        every { equipmentRepository.save(any()) } returns retired

        val result = equipmentService.retireEquipment(id, "Broken hinge")

        assertEquals(EquipmentState.RETIRED, result.state)
        assertEquals("Broken hinge", result.retiredReason)
    }

    @Test
    fun `retireEquipment should throw when equipment is not available`() {
        val id = UUID.randomUUID()
        every { equipmentRepository.findById(id) } returns sampleEquipment(id = id, state = EquipmentState.RESERVED)

        assertThrows<ConflictException> {
            equipmentService.retireEquipment(id, "No longer needed")
        }
    }

    @Test
    fun `retireEquipment should reject blank reason`() {
        assertThrows<BadRequestException> {
            equipmentService.retireEquipment(UUID.randomUUID(), "   ")
        }
    }

    @Test
    fun `retireEquipment should throw not found when missing`() {
        val id = UUID.randomUUID()
        every { equipmentRepository.findById(id) } returns null

        assertThrows<NotFoundException> {
            equipmentService.retireEquipment(id, "Missing")
        }
    }

    private fun sampleEquipment(
        id: UUID = UUID.randomUUID(),
        type: EquipmentType = EquipmentType.MONITOR,
        brand: String = "Dell",
        model: String = "U2723QE",
        state: EquipmentState = EquipmentState.AVAILABLE,
        conditionScore: Double = 0.9,
        purchaseDate: LocalDate = LocalDate.of(2024, 6, 1),
        retiredReason: String? = null
    ) = Equipment(
        id = id,
        type = type,
        brand = brand,
        model = model,
        state = state,
        conditionScore = conditionScore,
        purchaseDate = purchaseDate,
        retiredReason = retiredReason
    )
}
