package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import java.util.UUID

data class ProcessAllocationCommand(
    val allocationId: UUID,
    val policy: List<EquipmentPolicyRequirement>
)

