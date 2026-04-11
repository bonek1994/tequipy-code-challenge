package com.tequipy.challenge.adapter.api.web.dto

import com.tequipy.challenge.domain.model.AllocationState
import java.util.UUID

data class AllocationResponse(
    val id: UUID,
    val employeeId: UUID,
    val state: AllocationState,
    val policy: List<EquipmentPolicyRequirementRequest>,
    val allocatedEquipmentIds: List<UUID>,
    val allocatedEquipments: List<EquipmentResponse>,
    val idempotencyKey: UUID? = null
)

