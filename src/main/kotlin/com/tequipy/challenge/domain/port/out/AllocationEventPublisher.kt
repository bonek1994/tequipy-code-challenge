package com.tequipy.challenge.domain.port.out

import com.tequipy.challenge.domain.model.AllocationRequest

interface AllocationEventPublisher {
    fun publishAllocationCreated(allocation: AllocationRequest)
}
