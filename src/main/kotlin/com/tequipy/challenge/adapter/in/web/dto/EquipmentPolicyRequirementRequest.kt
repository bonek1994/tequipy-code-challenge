package com.tequipy.challenge.adapter.`in`.web.dto

import com.tequipy.challenge.domain.model.EquipmentType
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

data class EquipmentPolicyRequirementRequest(
    @field:NotNull
    val type: EquipmentType,

    @field:Min(1)
    val quantity: Int = 1,

    @field:DecimalMin("0.0")
    @field:DecimalMax("1.0")
    val minimumConditionScore: Double? = null,

    val preferredBrand: String? = null
)

