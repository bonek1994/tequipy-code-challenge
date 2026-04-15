package com.tequipy.challenge.domain.model

import java.util.UUID

data class AllocationCompletion(
    val allocationId: UUID,
    val state: AllocationState,
    val allocatedEquipmentIds: List<UUID> = emptyList()
)

