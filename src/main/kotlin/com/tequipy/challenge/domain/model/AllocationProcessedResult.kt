package com.tequipy.challenge.domain.model

import java.util.UUID

data class AllocationProcessedResult(
    val allocationId: UUID,
    val success: Boolean,
    val allocatedEquipmentIds: List<UUID> = emptyList()
)

