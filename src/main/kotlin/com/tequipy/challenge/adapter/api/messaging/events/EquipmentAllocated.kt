package com.tequipy.challenge.adapter.api.messaging.events

import java.time.Instant
import java.util.UUID

data class EquipmentAllocated(
    val results: List<AllocationProcessedResultMessage>,
    val timestamp: Instant = Instant.EPOCH
) {
    data class AllocationProcessedResultMessage(
        val id: UUID,
        val success: Boolean,
        val allocatedEquipmentIds: List<UUID> = emptyList()
    )
}

