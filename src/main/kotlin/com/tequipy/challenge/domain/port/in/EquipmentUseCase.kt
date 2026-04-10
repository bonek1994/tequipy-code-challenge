package com.tequipy.challenge.domain.port.`in`

import com.tequipy.challenge.domain.model.Equipment
import java.util.UUID

interface EquipmentUseCase {
    fun createEquipment(name: String, serialNumber: String): Equipment
    fun getEquipment(id: UUID): Equipment
    fun getAllEquipment(): List<Equipment>
    fun updateEquipment(id: UUID, name: String, serialNumber: String): Equipment
    fun deleteEquipment(id: UUID)
    fun assignEquipment(equipmentId: UUID, employeeId: UUID): Equipment
    fun unassignEquipment(equipmentId: UUID): Equipment
}
