package com.tequipy.challenge.domain.port.spi

import com.tequipy.challenge.domain.model.AllocationEntity
import java.util.UUID

interface AllocationEventPublisher {
    fun publishAllocationCreated(allocation: AllocationEntity)

    fun publishAllocationProcessed(
        allocationId: UUID,
        success: Boolean,
        allocatedEquipmentIds: List<UUID> = emptyList()
    )
}

