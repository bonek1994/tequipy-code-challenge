package com.tequipy.challenge.adapter.api.web.dto

import com.tequipy.challenge.domain.model.EquipmentType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull

@Schema(description = "A single equipment requirement within an allocation policy")
data class EquipmentPolicyRequirementRequest(
    @field:NotNull
    @Schema(description = "Required equipment type", example = "MAIN_COMPUTER", required = true)
    val type: EquipmentType,

    @field:Min(1)
    @Schema(description = "Number of units required (minimum 1)", example = "1")
    val quantity: Int = 1,

    @field:DecimalMin("0.0")
    @field:DecimalMax("1.0")
    @Schema(description = "Minimum acceptable condition score (0.0–1.0); null means no constraint", example = "0.7", nullable = true)
    val minimumConditionScore: Double? = null,

    @Schema(description = "Preferred equipment brand (soft preference, not mandatory)", example = "Apple", nullable = true)
    val preferredBrand: String? = null
)

