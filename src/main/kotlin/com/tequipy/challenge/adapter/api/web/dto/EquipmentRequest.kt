package com.tequipy.challenge.adapter.api.web.dto

import com.tequipy.challenge.domain.model.EquipmentType
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDate

data class EquipmentRequest(
    @field:NotNull
    val type: EquipmentType,

    @field:NotBlank
    val brand: String,

    @field:NotBlank
    val model: String,

    @field:DecimalMin("0.0")
    @field:DecimalMax("1.0")
    val conditionScore: Double,

    @field:NotNull
    val purchaseDate: LocalDate
)
