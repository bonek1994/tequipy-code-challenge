package com.tequipy.challenge.domain.port.out

import com.tequipy.challenge.domain.model.AllocationRequest
import java.util.UUID

interface AllocationRepository {
    fun save(allocationRequest: AllocationRequest): AllocationRequest
    fun findById(id: UUID): AllocationRequest?
}

