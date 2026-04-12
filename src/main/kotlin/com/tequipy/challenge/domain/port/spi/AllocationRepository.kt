package com.tequipy.challenge.domain.port.spi

import com.tequipy.challenge.domain.model.AllocationEntity
import com.tequipy.challenge.domain.model.AllocationState
import java.util.UUID

interface AllocationRepository {
    fun save(allocationEntity: AllocationEntity): AllocationEntity
    fun findById(id: UUID): AllocationEntity?
    fun findByIdempotencyKey(key: UUID): AllocationEntity?
    fun completePending(id: UUID, state: AllocationState, allocatedEquipmentIds: List<UUID>): AllocationEntity?
}

