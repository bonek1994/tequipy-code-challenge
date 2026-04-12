package com.tequipy.challenge.adapter.spi.persistence.adapter

import com.tequipy.challenge.adapter.spi.persistence.mapper.AllocationRequestEntityMapper
import com.tequipy.challenge.adapter.spi.persistence.repository.AllocationRequestJdbcRepository
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AllocationPersistenceAdapter(
    private val jdbcRepository: AllocationRequestJdbcRepository,
    private val mapper: AllocationRequestEntityMapper
) : AllocationRepository {

    override fun save(allocationRequest: AllocationRequest): AllocationRequest {
        val entity = mapper.toEntity(allocationRequest)
        return mapper.toDomain(jdbcRepository.save(entity))
    }

    override fun findById(id: UUID): AllocationRequest? {
        return jdbcRepository.findById(id)?.let(mapper::toDomain)
    }

    override fun findByIdempotencyKey(key: UUID): AllocationRequest? {
        return jdbcRepository.findByIdempotencyKey(key)?.let(mapper::toDomain)
    }

    override fun completePending(id: UUID, state: AllocationState, allocatedEquipmentIds: List<UUID>): AllocationRequest? {
        return jdbcRepository.completePending(id, state, allocatedEquipmentIds)?.let(mapper::toDomain)
    }
}
