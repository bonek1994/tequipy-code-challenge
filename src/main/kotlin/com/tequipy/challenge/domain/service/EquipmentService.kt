package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.port.`in`.EquipmentUseCase
import com.tequipy.challenge.domain.port.out.EmployeeRepository
import com.tequipy.challenge.domain.port.out.EquipmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class EquipmentService(
    private val equipmentRepository: EquipmentRepository,
    private val employeeRepository: EmployeeRepository
) : EquipmentUseCase {

    override fun createEquipment(name: String, serialNumber: String): Equipment {
        val equipment = Equipment(
            id = UUID.randomUUID(),
            name = name,
            serialNumber = serialNumber,
            employeeId = null
        )
        return equipmentRepository.save(equipment)
    }

    @Transactional(readOnly = true)
    override fun getEquipment(id: UUID): Equipment {
        return equipmentRepository.findById(id)
            ?: throw NotFoundException("Equipment not found with id: $id")
    }

    @Transactional(readOnly = true)
    override fun getAllEquipment(): List<Equipment> {
        return equipmentRepository.findAll()
    }

    override fun updateEquipment(id: UUID, name: String, serialNumber: String): Equipment {
        val existing = equipmentRepository.findById(id)
            ?: throw NotFoundException("Equipment not found with id: $id")
        val updated = existing.copy(name = name, serialNumber = serialNumber)
        return equipmentRepository.save(updated)
    }

    override fun deleteEquipment(id: UUID) {
        if (!equipmentRepository.existsById(id)) {
            throw NotFoundException("Equipment not found with id: $id")
        }
        equipmentRepository.deleteById(id)
    }

    override fun assignEquipment(equipmentId: UUID, employeeId: UUID): Equipment {
        val equipment = equipmentRepository.findById(equipmentId)
            ?: throw NotFoundException("Equipment not found with id: $equipmentId")
        if (!employeeRepository.existsById(employeeId)) {
            throw NotFoundException("Employee not found with id: $employeeId")
        }
        val assigned = equipment.copy(employeeId = employeeId)
        return equipmentRepository.save(assigned)
    }

    override fun unassignEquipment(equipmentId: UUID): Equipment {
        val equipment = equipmentRepository.findById(equipmentId)
            ?: throw NotFoundException("Equipment not found with id: $equipmentId")
        val unassigned = equipment.copy(employeeId = null)
        return equipmentRepository.save(unassigned)
    }
}
