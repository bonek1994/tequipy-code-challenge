package com.tequipy.challenge.adapter.`in`.web.dto

import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import java.time.LocalDate
import java.util.UUID

data class EquipmentResponse(
    val id: UUID,
    val type: EquipmentType,
    val brand: String,
    val model: String,
    val state: EquipmentState,
    val conditionScore: Double,
    val purchaseDate: LocalDate,
    val retiredReason: String?
)
