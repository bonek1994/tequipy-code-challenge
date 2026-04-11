package com.tequipy.challenge.adapter.api.web.dto

import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.util.UUID

@Schema(description = "Equipment details")
data class EquipmentResponse(
    @Schema(description = "Unique identifier of the equipment")
    val id: UUID,
    @Schema(description = "Equipment type")
    val type: EquipmentType,
    @Schema(description = "Equipment brand", example = "Apple")
    val brand: String,
    @Schema(description = "Equipment model name", example = "MacBook Pro 14\"")
    val model: String,
    @Schema(description = "Current equipment state")
    val state: EquipmentState,
    @Schema(description = "Condition score between 0.0 (worst) and 1.0 (best)", example = "0.95")
    val conditionScore: Double,
    @Schema(description = "Date the equipment was purchased", example = "2024-01-15")
    val purchaseDate: LocalDate,
    @Schema(description = "Reason for retirement (present only when state is RETIRED)", nullable = true)
    val retiredReason: String?
)
