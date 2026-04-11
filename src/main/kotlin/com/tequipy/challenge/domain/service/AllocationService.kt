package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.`in`.AllocationUseCase
import com.tequipy.challenge.domain.port.out.AllocationEventPublisher
import com.tequipy.challenge.domain.port.out.AllocationRepository
import com.tequipy.challenge.domain.port.out.EquipmentRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class AllocationService(
    private val allocationRepository: AllocationRepository,
    private val equipmentRepository: EquipmentRepository,
    private val allocationEventPublisher: AllocationEventPublisher
) : AllocationUseCase {

    private val logger = KotlinLogging.logger {}

    override fun createAllocation(
        policy: List<EquipmentPolicyRequirement>
    ): AllocationRequest {
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

    @Transactional(readOnly = true)
    override fun getAllocation(id: UUID): AllocationRequest {
        return allocationRepository.findById(id)
            ?: throw NotFoundException("Allocation not found with id: $id")
    }

    override fun confirmAllocation(id: UUID): AllocationRequest {
        val allocation = getAllocation(id)
        if (allocation.state != AllocationState.ALLOCATED) {
            throw ConflictException("Only allocated requests can be confirmed")
        }

        logger.info { "Confirming allocation: id=$id" }
        val equipments = equipmentRepository.findByIds(allocation.allocatedEquipmentIds)
        equipmentRepository.saveAll(equipments.map { it.copy(state = EquipmentState.ASSIGNED) })

        val confirmed = allocationRepository.save(allocation.copy(state = AllocationState.CONFIRMED))
        logger.info { "Allocation confirmed: id=$id" }
        return confirmed
    }

    override fun cancelAllocation(id: UUID): AllocationRequest {
        val allocation = getAllocation(id)
        if (allocation.state !in setOf(AllocationState.PENDING, AllocationState.ALLOCATED, AllocationState.FAILED)) {
            throw ConflictException("Only pending, allocated or failed requests can be cancelled")
        }

        logger.info { "Cancelling allocation: id=$id" }
        if (allocation.allocatedEquipmentIds.isNotEmpty()) {
            val equipments = equipmentRepository.findByIds(allocation.allocatedEquipmentIds)
            equipmentRepository.saveAll(
                equipments.map {
                    if (it.state == EquipmentState.RESERVED) it.copy(state = EquipmentState.AVAILABLE) else it
                }
            )
        }

        val cancelled = allocationRepository.save(
            allocation.copy(
                state = AllocationState.CANCELLED,
                allocatedEquipmentIds = emptyList()
            )
        )
        logger.info { "Allocation cancelled: id=$id" }
        return cancelled
    }
}
