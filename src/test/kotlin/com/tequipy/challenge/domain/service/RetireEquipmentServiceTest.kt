package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.command.RetireEquipmentCommand
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

class RetireEquipmentServiceTest {
    private val equipmentRepository: EquipmentRepository = mockk()
    private val service = RetireEquipmentService(equipmentRepository)

    @Test
    fun `retireEquipment should set retired state with reason`() {
        val id = UUID.randomUUID()
        val existing = sampleEquipment(id = id, state = EquipmentState.AVAILABLE)
        val retired = existing.copy(state = EquipmentState.RETIRED, retiredReason = "Broken hinge")
        every { equipmentRepository.findById(id) } returns existing
        every { equipmentRepository.update(any()) } returns retired

        val result = service.retireEquipment(RetireEquipmentCommand(id, "Broken hinge"))

        assertEquals(EquipmentState.RETIRED, result.state)
        assertEquals("Broken hinge", result.retiredReason)
    }

    @Test
    fun `retireEquipment should throw when equipment is not available`() {
        val id = UUID.randomUUID()
        every { equipmentRepository.findById(id) } returns sampleEquipment(id = id, state = EquipmentState.RESERVED)

        assertThrows(ConflictException::class.java) {
            service.retireEquipment(RetireEquipmentCommand(id, "No longer needed"))
        }
    }

    @Test
    fun `retireEquipment should reject blank reason`() {
        assertThrows(BadRequestException::class.java) {
            service.retireEquipment(RetireEquipmentCommand(UUID.randomUUID(), "   "))
        }
    }

    @Test
    fun `retireEquipment should throw not found when missing`() {
        val id = UUID.randomUUID()
        every { equipmentRepository.findById(id) } returns null

        assertThrows(NotFoundException::class.java) {
            service.retireEquipment(RetireEquipmentCommand(id, "Missing"))
        }
    }

    private fun sampleEquipment(id: UUID, state: EquipmentState) = Equipment(
        id = id,
        type = EquipmentType.MONITOR,
        brand = "Dell",
        model = "U2723QE",
        state = state,
        conditionScore = 0.9,
        purchaseDate = LocalDate.of(2024, 6, 1),
        retiredReason = null
    )
}

