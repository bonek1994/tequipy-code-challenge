package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class RegisterEquipmentServiceTest {

    private val equipmentRepository: EquipmentRepository = mockk()
    private val service = RegisterEquipmentService(equipmentRepository)

    @Test
    fun `registerEquipment should save available equipment`() {
        val purchaseDate = LocalDate.of(2025, 1, 10)
        every { equipmentRepository.save(any()) } answers { firstArg() }

        val result = service.registerEquipment(
            type = EquipmentType.MAIN_COMPUTER,
            brand = "Apple",
            model = "MacBook Pro",
            conditionScore = 0.92,
            purchaseDate = purchaseDate
        )

        assertEquals(EquipmentState.AVAILABLE, result.state)
        verify { equipmentRepository.save(withArg { assertEquals(EquipmentState.AVAILABLE, it.state) }) }
    }

    @Test
    fun `registerEquipment should reject invalid condition score`() {
        assertThrows(BadRequestException::class.java) {
            service.registerEquipment(
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

        val minResult = service.registerEquipment(
            type = EquipmentType.KEYBOARD,
            brand = "Logitech",
            model = "MX Keys",
            conditionScore = 0.0,
            purchaseDate = LocalDate.of(2024, 5, 1)
        )
        val maxResult = service.registerEquipment(
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
                assertThrows(BadRequestException::class.java) {
                    service.registerEquipment(
                        type = EquipmentType.MONITOR,
                        brand = "   ",
                        model = "U2723QE",
                        conditionScore = 0.8,
                        purchaseDate = LocalDate.now()
                    )
                }
            },
            {
                assertThrows(BadRequestException::class.java) {
                    service.registerEquipment(
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
}

