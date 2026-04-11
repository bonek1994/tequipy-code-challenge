package com.tequipy.challenge.adapter.api.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "Request body for retiring equipment")
data class RetireEquipmentRequest(
    @field:NotBlank
    @Schema(description = "Reason for retiring the equipment", example = "Hardware failure — screen cracked", required = true)
    val reason: String
)

