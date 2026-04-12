package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.port.api.CreateAllocationUseCase
import com.tequipy.challenge.domain.port.spi.AllocationEventPublisher
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class CreateAllocationService(
    private val allocationRepository: AllocationRepository,
    private val allocationEventPublisher: AllocationEventPublisher
) : CreateAllocationUseCase {

    private val logger = KotlinLogging.logger {}

    override fun createAllocation(policy: List<EquipmentPolicyRequirement>): AllocationRequest {
        if (policy.isEmpty()) {
            throw BadRequestException("Allocation policy must not be empty")
        }
        if (policy.any { it.quantity <= 0 }) {
            throw BadRequestException("Allocation policy quantity must be greater than zero")
        }
        if (policy.any { it.minimumConditionScore != null && it.minimumConditionScore !in 0.0..1.0 }) {
            throw BadRequestException("minimumConditionScore must be between 0.0 and 1.0")
        }

        logger.info { "Creating allocation with ${policy.size} policy requirement(s)" }
        val allocation = allocationRepository.save(
            AllocationRequest(
                id = UUID.randomUUID(),
                policy = policy,
                state = AllocationState.PENDING,
                allocatedEquipmentIds = emptyList()
            )
        )
        logger.info { "Allocation created: id=${allocation.id}" }

        allocationEventPublisher.publishAllocationCreated(allocation)
        return allocation
    }
}

