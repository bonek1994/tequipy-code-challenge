package com.tequipy.challenge.adapter.spi.persistence.entity

import com.tequipy.challenge.domain.model.AllocationState
import java.util.UUID

data class AllocationEntity(
    val id: UUID,
    val state: AllocationState,
    val policy: List<EquipmentPolicyRequirementEmbeddable>,
    val allocatedEquipmentIds: List<UUID>,
    val idempotencyKey: UUID? = null
)

