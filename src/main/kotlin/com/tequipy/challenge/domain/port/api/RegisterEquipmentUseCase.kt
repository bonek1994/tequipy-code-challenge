package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentType
import java.time.LocalDate

interface RegisterEquipmentUseCase {
    fun registerEquipment(
        type: EquipmentType,
        brand: String,
        model: String,
        conditionScore: Double,
        purchaseDate: LocalDate
    ): Equipment
}

