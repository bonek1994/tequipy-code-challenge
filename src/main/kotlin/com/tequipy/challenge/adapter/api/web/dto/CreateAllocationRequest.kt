package com.tequipy.challenge.adapter.api.web.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class CreateAllocationRequest(
    @field:NotNull
    val employeeId: UUID,

    @field:Valid
    @field:NotEmpty
    val policy: List<EquipmentPolicyRequirementRequest>
)

