package com.tequipy.challenge.domain.model

import java.util.UUID

data class AllocationProcessingRecord(
    val allocationId: UUID,
    val state: AllocationProcessingState,
    val allocatedEquipmentIds: List<UUID> = emptyList()
)

