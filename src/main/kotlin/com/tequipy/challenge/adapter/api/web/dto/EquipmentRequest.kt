package com.tequipy.challenge.adapter.api.web.dto

import com.tequipy.challenge.domain.model.EquipmentType
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.time.LocalDate

@Schema(description = "Request body for registering new equipment")
data class EquipmentRequest(
    @field:NotNull
    @Schema(description = "Equipment type", example = "MAIN_COMPUTER", required = true)
    val type: EquipmentType,

    @field:NotBlank
    @Schema(description = "Equipment brand", example = "Apple", required = true)
    val brand: String,

    @field:NotBlank
    @Schema(description = "Equipment model name", example = "MacBook Pro 14\"", required = true)
    val model: String,

    @field:DecimalMin("0.0")
    @field:DecimalMax("1.0")
    @Schema(description = "Condition score between 0.0 (worst) and 1.0 (best)", example = "0.95", minimum = "0.0", maximum = "1.0", required = true)
    val conditionScore: Double,

    @field:NotNull
    @Schema(description = "Date the equipment was purchased", example = "2024-01-15", required = true)
    val purchaseDate: LocalDate
)
