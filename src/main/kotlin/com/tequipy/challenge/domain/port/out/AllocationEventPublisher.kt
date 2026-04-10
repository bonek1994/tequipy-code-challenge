package com.tequipy.challenge.domain.port.out

import java.util.UUID

interface AllocationEventPublisher {
    fun publishAllocationCreated(allocationId: UUID)
}
