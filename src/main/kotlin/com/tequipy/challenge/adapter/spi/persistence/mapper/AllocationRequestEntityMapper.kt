package com.tequipy.challenge.adapter.spi.persistence.mapper

import com.tequipy.challenge.adapter.spi.persistence.entity.AllocationRequestEntity
import com.tequipy.challenge.adapter.spi.persistence.entity.EquipmentPolicyRequirementEmbeddable
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import org.springframework.stereotype.Component

@Component
class AllocationRequestEntityMapper {

    fun toDomain(entity: AllocationRequestEntity): AllocationRequest = AllocationRequest(
        id = entity.id,
        employeeId = entity.employeeId,
        state = entity.state,
        policy = entity.policy.map(::toDomainRequirement),
        allocatedEquipmentIds = entity.allocatedEquipmentIds,
        idempotencyKey = entity.idempotencyKey
    )

    fun toEntity(domain: AllocationRequest): AllocationRequestEntity = AllocationRequestEntity(
        id = domain.id,
        employeeId = domain.employeeId,
        state = domain.state,
        policy = domain.policy.map(::toEntityRequirement),
        allocatedEquipmentIds = domain.allocatedEquipmentIds,
        idempotencyKey = domain.idempotencyKey
    )

    private fun toDomainRequirement(requirement: EquipmentPolicyRequirementEmbeddable) = EquipmentPolicyRequirement(
        type = requirement.type,
        quantity = requirement.quantity,
        minimumConditionScore = requirement.minimumConditionScore,
        preferredBrand = requirement.preferredBrand
    )

    private fun toEntityRequirement(requirement: EquipmentPolicyRequirement) = EquipmentPolicyRequirementEmbeddable(
        type = requirement.type,
        quantity = requirement.quantity,
        minimumConditionScore = requirement.minimumConditionScore,
        preferredBrand = requirement.preferredBrand
    )
}

