package com.tequipy.challenge.domain.port.`in`

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import java.time.LocalDate
import java.util.UUID

interface EquipmentUseCase {
    fun registerEquipment(
        type: EquipmentType,
        brand: String,
        model: String,
        conditionScore: Double,
        purchaseDate: LocalDate
    ): Equipment

    fun getEquipment(id: UUID): Equipment
    fun listEquipment(state: EquipmentState?): List<Equipment>
    fun retireEquipment(id: UUID, reason: String): Equipment
}
