package com.tequipy.challenge.adapter.out.persistence.entity

import com.tequipy.challenge.domain.model.AllocationState
import java.util.UUID

data class AllocationRequestEntity(
    val id: UUID,
    val employeeId: UUID,
    val state: AllocationState,
    val policy: List<EquipmentPolicyRequirementEmbeddable>,
    val allocatedEquipmentIds: List<UUID>
)

