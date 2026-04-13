package com.tequipy.challenge.domain.command

import com.tequipy.challenge.domain.model.EquipmentType
import java.time.LocalDate

data class RegisterEquipmentCommand(
    val type: EquipmentType,
    val brand: String,
    val model: String,
    val conditionScore: Double,
    val purchaseDate: LocalDate
)

