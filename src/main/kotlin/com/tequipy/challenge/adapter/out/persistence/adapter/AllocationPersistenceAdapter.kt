package com.tequipy.challenge.adapter.out.persistence.adapter

import com.tequipy.challenge.adapter.out.persistence.mapper.AllocationRequestEntityMapper
import com.tequipy.challenge.adapter.out.persistence.repository.AllocationRequestJdbcRepository
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.port.out.AllocationRepository
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
}


