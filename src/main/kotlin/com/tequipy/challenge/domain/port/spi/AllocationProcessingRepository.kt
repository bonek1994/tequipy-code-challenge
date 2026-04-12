package com.tequipy.challenge.domain.port.spi

import com.tequipy.challenge.domain.model.AllocationProcessingRecord
import com.tequipy.challenge.domain.model.AllocationProcessingState
import java.util.UUID

interface AllocationProcessingRepository {
    fun tryStart(allocationId: UUID): Boolean
    fun findById(allocationId: UUID): AllocationProcessingRecord?
    fun complete(
        allocationId: UUID,
        state: AllocationProcessingState,
        allocatedEquipmentIds: List<UUID>
    ): AllocationProcessingRecord
}

