package com.tequipy.challenge.domain.port.spi

import com.tequipy.challenge.domain.model.AllocationRequest
import java.util.UUID

interface AllocationEventPublisher {
    fun publishAllocationCreated(allocation: AllocationRequest)

    fun publishAllocationProcessed(
        allocationId: UUID,
        success: Boolean,
        allocatedEquipmentIds: List<UUID> = emptyList()
    )
}
