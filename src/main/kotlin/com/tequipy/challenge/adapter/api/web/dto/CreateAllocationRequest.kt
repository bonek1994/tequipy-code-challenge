package com.tequipy.challenge.adapter.api.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty

data class CreateAllocationRequest(
    @field:Valid
    @field:NotEmpty
    val policy: List<EquipmentPolicyRequirementRequest>
)

