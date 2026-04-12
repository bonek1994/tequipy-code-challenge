package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.port.api.GetAllocationUseCase
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class GetAllocationService(
    private val allocationRepository: AllocationRepository
) : GetAllocationUseCase {

    override fun getAllocation(id: UUID): AllocationRequest {
        return allocationRepository.findById(id)
            ?: throw NotFoundException("Allocation not found with id: $id")
    }
}

