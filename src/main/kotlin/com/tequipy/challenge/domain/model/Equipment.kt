package com.tequipy.challenge.domain.model

import java.time.LocalDate
import java.util.UUID

data class Equipment(
    val id: UUID,
    val type: EquipmentType,
    val brand: String,
    val model: String,
    val state: EquipmentState,
    val conditionScore: Double,
    val purchaseDate: LocalDate,
    val retiredReason: String? = null
)
