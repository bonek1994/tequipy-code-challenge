package com.tequipy.challenge.domain.port.out

import com.tequipy.challenge.domain.model.Equipment
import java.util.UUID

interface EquipmentRepository {
    fun save(equipment: Equipment): Equipment
    fun findById(id: UUID): Equipment?
    fun findAll(): List<Equipment>
    fun findByEmployeeId(employeeId: UUID): List<Equipment>
    fun deleteById(id: UUID)
    fun existsById(id: UUID): Boolean
}
