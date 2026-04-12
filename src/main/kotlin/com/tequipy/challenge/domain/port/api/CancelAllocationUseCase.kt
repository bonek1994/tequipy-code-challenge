package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.model.AllocationRequest
import java.util.UUID

interface CancelAllocationUseCase {
    fun cancelAllocation(id: UUID): AllocationRequest
}

