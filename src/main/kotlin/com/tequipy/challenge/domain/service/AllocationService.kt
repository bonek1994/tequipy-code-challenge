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

    override fun createAllocation(employeeId: UUID, policy: List<EquipmentPolicyRequirement>): AllocationRequest {
        if (policy.isEmpty()) {
            throw BadRequestException("Allocation policy must not be empty")
        }
        if (policy.any { it.quantity <= 0 }) {
            throw BadRequestException("Allocation policy quantity must be greater than zero")
        }
        if (policy.any { it.minimumConditionScore != null && it.minimumConditionScore !in 0.0..1.0 }) {
            throw BadRequestException("minimumConditionScore must be between 0.0 and 1.0")
        }

        val allocation = allocationRepository.save(
            AllocationRequest(
                id = UUID.randomUUID(),
                employeeId = employeeId,
                policy = policy,
                state = AllocationState.PENDING,
                allocatedEquipmentIds = emptyList()
            )
        )

        allocationEventPublisher.publishAllocationCreated(allocation.id)
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

        val equipments = equipmentRepository.findByIds(allocation.allocatedEquipmentIds)
        equipmentRepository.saveAll(equipments.map { it.copy(state = EquipmentState.ASSIGNED) })

        return allocationRepository.save(allocation.copy(state = AllocationState.CONFIRMED))
    }

    override fun cancelAllocation(id: UUID): AllocationRequest {
        val allocation = getAllocation(id)
        if (allocation.state !in setOf(AllocationState.PENDING, AllocationState.ALLOCATED, AllocationState.FAILED)) {
            throw ConflictException("Only pending, allocated or failed requests can be cancelled")
        }

        if (allocation.allocatedEquipmentIds.isNotEmpty()) {
            val equipments = equipmentRepository.findByIds(allocation.allocatedEquipmentIds)
            equipmentRepository.saveAll(
                equipments.map {
                    if (it.state == EquipmentState.RESERVED) it.copy(state = EquipmentState.AVAILABLE) else it
                }
            )
        }

        return allocationRepository.save(
            allocation.copy(
                state = AllocationState.CANCELLED,
                allocatedEquipmentIds = emptyList()
            )
        )
    }
}



