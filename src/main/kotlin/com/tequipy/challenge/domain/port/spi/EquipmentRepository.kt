package com.tequipy.challenge.domain.port.spi

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import java.util.UUID

interface EquipmentRepository {
    fun create(equipment: Equipment): Equipment
    fun update(equipment: Equipment): Equipment
    fun updateAll(equipment: List<Equipment>): List<Equipment>
    fun findById(id: UUID): Equipment?
    fun findAll(): List<Equipment>
    fun findByIds(ids: List<UUID>): List<Equipment>
    fun findByState(state: EquipmentState): List<Equipment>
    fun findAvailableWithMinConditionScore(types: Set<EquipmentType>, minScore: Double): List<Equipment>
    fun findByIdsForUpdate(ids: List<UUID>, minConditionScore: Double = 0.0): List<Equipment>
    fun existsById(id: UUID): Boolean
}
