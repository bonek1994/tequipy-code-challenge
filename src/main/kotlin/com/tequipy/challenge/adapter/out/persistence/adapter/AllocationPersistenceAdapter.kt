package com.tequipy.challenge.adapter.out.persistence.adapter

import com.tequipy.challenge.adapter.out.persistence.mapper.AllocationRequestEntityMapper
import com.tequipy.challenge.adapter.out.persistence.repository.AllocationRequestJpaRepository
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.port.out.AllocationRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AllocationPersistenceAdapter(
    private val jpaRepository: AllocationRequestJpaRepository,
    private val mapper: AllocationRequestEntityMapper
) : AllocationRepository {

    override fun save(allocationRequest: AllocationRequest): AllocationRequest {
        val entity = mapper.toEntity(allocationRequest)
        return mapper.toDomain(jpaRepository.save(entity))
    }

    override fun findById(id: UUID): AllocationRequest? {
        return jpaRepository.findById(id).orElse(null)?.let(mapper::toDomain)
    }
}

