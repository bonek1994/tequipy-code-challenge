package com.tequipy.challenge.adapter.spi.persistence.adapter

import com.tequipy.challenge.adapter.spi.persistence.mapper.AllocationEntityMapper
import com.tequipy.challenge.adapter.spi.persistence.repository.AllocationJdbcRepository
import com.tequipy.challenge.domain.model.AllocationEntity
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AllocationPersistenceAdapter(
    private val jdbcRepository: AllocationJdbcRepository,
    private val mapper: AllocationEntityMapper
) : AllocationRepository {

    override fun save(allocationEntity: AllocationEntity): AllocationEntity {
        val entity = mapper.toEntity(allocationEntity)
        return mapper.toDomain(jdbcRepository.save(entity))
    }

    override fun findById(id: UUID): AllocationEntity? {
        return jdbcRepository.findById(id)?.let(mapper::toDomain)
    }

    override fun findByIdempotencyKey(key: UUID): AllocationEntity? {
        return jdbcRepository.findByIdempotencyKey(key)?.let(mapper::toDomain)
    }

    override fun completePending(id: UUID, state: AllocationState, allocatedEquipmentIds: List<UUID>): AllocationEntity? {
        return jdbcRepository.completePending(id, state, allocatedEquipmentIds)?.let(mapper::toDomain)
    }
}

