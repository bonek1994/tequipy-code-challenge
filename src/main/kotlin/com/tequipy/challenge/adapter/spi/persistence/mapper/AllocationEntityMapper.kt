package com.tequipy.challenge.adapter.spi.persistence.mapper

import com.tequipy.challenge.adapter.spi.persistence.entity.AllocationEntity as AllocationRowEntity
import com.tequipy.challenge.adapter.spi.persistence.entity.EquipmentPolicyRequirementEmbeddable
import com.tequipy.challenge.domain.model.AllocationEntity as AllocationDomainEntity
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import org.springframework.stereotype.Component

@Component
class AllocationEntityMapper {

    fun toDomain(entity: AllocationRowEntity): AllocationDomainEntity = AllocationDomainEntity(
        id = entity.id,
        state = entity.state,
        policy = entity.policy.map(::toDomainRequirement),
        allocatedEquipmentIds = entity.allocatedEquipmentIds,
        idempotencyKey = entity.idempotencyKey
    )

    fun toEntity(domain: AllocationDomainEntity): AllocationRowEntity = AllocationRowEntity(
        id = domain.id,
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

