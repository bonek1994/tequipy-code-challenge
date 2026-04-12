package com.tequipy.challenge.domain.port.spi

import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import java.util.UUID

interface AllocationRepository {
    fun save(allocationRequest: AllocationRequest): AllocationRequest
    fun findById(id: UUID): AllocationRequest?
    fun findByIdempotencyKey(key: UUID): AllocationRequest?
    fun completePending(id: UUID, state: AllocationState, allocatedEquipmentIds: List<UUID>): AllocationRequest?
}
