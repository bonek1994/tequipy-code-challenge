package com.tequipy.challenge.adapter.api.web.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty

@Schema(description = "Request body for creating a new allocation request")
data class CreateAllocationRequest(
    @field:Valid
    @field:NotEmpty
    @Schema(description = "List of equipment requirements; must contain at least one entry", required = true)
    val policy: List<EquipmentPolicyRequirementRequest>
)
