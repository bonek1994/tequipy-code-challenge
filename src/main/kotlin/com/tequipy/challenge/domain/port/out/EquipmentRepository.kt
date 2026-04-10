package com.tequipy.challenge.domain.port.out

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import java.util.UUID

interface EquipmentRepository {
    fun save(equipment: Equipment): Equipment
    fun saveAll(equipment: List<Equipment>): List<Equipment>
    fun findById(id: UUID): Equipment?
    fun findAll(): List<Equipment>
    fun findByIds(ids: List<UUID>): List<Equipment>
    fun findByState(state: EquipmentState): List<Equipment>
    fun findByIdsForUpdate(ids: List<UUID>): List<Equipment>
    fun existsById(id: UUID): Boolean
}
