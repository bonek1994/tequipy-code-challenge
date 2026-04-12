package com.tequipy.challenge.domain.model

import java.util.UUID

data class AllocationEntity(
    val id: UUID,
    val policy: List<EquipmentPolicyRequirement>,
    val state: AllocationState,
    val allocatedEquipmentIds: List<UUID> = emptyList(),
    val idempotencyKey: UUID? = null
)

