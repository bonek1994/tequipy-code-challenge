package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.port.out.EmployeeRepository
import com.tequipy.challenge.domain.port.out.EquipmentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class EquipmentServiceTest {

    private val equipmentRepository: EquipmentRepository = mockk()
    private val employeeRepository: EmployeeRepository = mockk()
    private val equipmentService = EquipmentService(equipmentRepository, employeeRepository)

    @Test
    fun `createEquipment should save and return equipment`() {
        val name = "Laptop"
        val serialNumber = "SN-001"
        val saved = Equipment(UUID.randomUUID(), name, serialNumber, null)

        every { equipmentRepository.save(any()) } returns saved

        val result = equipmentService.createEquipment(name, serialNumber)

        assertEquals(saved, result)
        verify { equipmentRepository.save(any()) }
    }

    @Test
    fun `getEquipment should return equipment when found`() {
        val id = UUID.randomUUID()
        val equipment = Equipment(id, "Monitor", "SN-002", null)

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
    fun `getAllEquipment should return all equipment`() {
        val equipmentList = listOf(
            Equipment(UUID.randomUUID(), "Laptop", "SN-001", null),
            Equipment(UUID.randomUUID(), "Mouse", "SN-002", null)
        )

        every { equipmentRepository.findAll() } returns equipmentList

        val result = equipmentService.getAllEquipment()

        assertEquals(2, result.size)
        assertEquals(equipmentList, result)
    }

    @Test
    fun `updateEquipment should update and return equipment when found`() {
        val id = UUID.randomUUID()
        val existing = Equipment(id, "Old Laptop", "SN-OLD", null)
        val updated = existing.copy(name = "New Laptop", serialNumber = "SN-NEW")

        every { equipmentRepository.findById(id) } returns existing
        every { equipmentRepository.save(updated) } returns updated

        val result = equipmentService.updateEquipment(id, "New Laptop", "SN-NEW")

        assertEquals(updated, result)
        verify { equipmentRepository.save(updated) }
    }

    @Test
    fun `updateEquipment should throw NotFoundException when not found`() {
        val id = UUID.randomUUID()

        every { equipmentRepository.findById(id) } returns null

        assertThrows<NotFoundException> {
            equipmentService.updateEquipment(id, "Name", "SN-001")
        }
    }

    @Test
    fun `deleteEquipment should delete when equipment exists`() {
        val id = UUID.randomUUID()

        every { equipmentRepository.existsById(id) } returns true
        every { equipmentRepository.deleteById(id) } returns Unit

        assertDoesNotThrow {
            equipmentService.deleteEquipment(id)
        }

        verify { equipmentRepository.deleteById(id) }
    }

    @Test
    fun `deleteEquipment should throw NotFoundException when not found`() {
        val id = UUID.randomUUID()

        every { equipmentRepository.existsById(id) } returns false

        assertThrows<NotFoundException> {
            equipmentService.deleteEquipment(id)
        }
    }

    @Test
    fun `assignEquipment should assign employee to equipment`() {
        val equipmentId = UUID.randomUUID()
        val employeeId = UUID.randomUUID()
        val equipment = Equipment(equipmentId, "Laptop", "SN-001", null)
        val assigned = equipment.copy(employeeId = employeeId)

        every { equipmentRepository.findById(equipmentId) } returns equipment
        every { employeeRepository.existsById(employeeId) } returns true
        every { equipmentRepository.save(assigned) } returns assigned

        val result = equipmentService.assignEquipment(equipmentId, employeeId)

        assertEquals(employeeId, result.employeeId)
        verify { equipmentRepository.save(assigned) }
    }

    @Test
    fun `assignEquipment should throw NotFoundException when equipment not found`() {
        val equipmentId = UUID.randomUUID()
        val employeeId = UUID.randomUUID()

        every { equipmentRepository.findById(equipmentId) } returns null

        assertThrows<NotFoundException> {
            equipmentService.assignEquipment(equipmentId, employeeId)
        }
    }

    @Test
    fun `assignEquipment should throw NotFoundException when employee not found`() {
        val equipmentId = UUID.randomUUID()
        val employeeId = UUID.randomUUID()
        val equipment = Equipment(equipmentId, "Laptop", "SN-001", null)

        every { equipmentRepository.findById(equipmentId) } returns equipment
        every { employeeRepository.existsById(employeeId) } returns false

        assertThrows<NotFoundException> {
            equipmentService.assignEquipment(equipmentId, employeeId)
        }
    }

    @Test
    fun `unassignEquipment should remove employee from equipment`() {
        val equipmentId = UUID.randomUUID()
        val employeeId = UUID.randomUUID()
        val equipment = Equipment(equipmentId, "Laptop", "SN-001", employeeId)
        val unassigned = equipment.copy(employeeId = null)

        every { equipmentRepository.findById(equipmentId) } returns equipment
        every { equipmentRepository.save(unassigned) } returns unassigned

        val result = equipmentService.unassignEquipment(equipmentId)

        assertNull(result.employeeId)
        verify { equipmentRepository.save(unassigned) }
    }
}
