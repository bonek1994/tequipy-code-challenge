package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.command.CreateAllocationCommand
import com.tequipy.challenge.domain.model.AllocationEntity
import com.tequipy.challenge.domain.model.AllocationState
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

    override fun createAllocation(command: CreateAllocationCommand): AllocationEntity {
        if (command.policy.isEmpty()) {
            throw BadRequestException("Allocation policy must not be empty")
        }
        if (command.policy.any { it.quantity <= 0 }) {
            throw BadRequestException("Allocation policy quantity must be greater than zero")
        }
        if (command.policy.any { it.minimumConditionScore != null && it.minimumConditionScore !in 0.0..1.0 }) {
            throw BadRequestException("minimumConditionScore must be between 0.0 and 1.0")
        }

        logger.info { "Creating allocation with ${command.policy.size} policy requirement(s)" }
        val allocation = allocationRepository.save(
            AllocationEntity(
                id = UUID.randomUUID(),
                policy = command.policy,
                state = AllocationState.PENDING,
                allocatedEquipmentIds = emptyList()
            )
        )
        logger.info { "Allocation created: id=${allocation.id}" }

        allocationEventPublisher.publishAllocationCreated(allocation)
        return allocation
    }
}


