package com.tequipy.challenge.adapter.api.web.mapper

import com.tequipy.challenge.adapter.api.web.dto.AllocationResponse
import com.tequipy.challenge.adapter.api.web.dto.EquipmentPolicyRequirementRequest
import com.tequipy.challenge.adapter.api.web.dto.EquipmentResponse
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import org.springframework.stereotype.Component

@Component
class AllocationMapper {

    fun toDomain(requirement: EquipmentPolicyRequirementRequest) = EquipmentPolicyRequirement(
        type = requirement.type,
        quantity = requirement.quantity,
        minimumConditionScore = requirement.minimumConditionScore,
        preferredBrand = requirement.preferredBrand
    )

    fun toResponse(allocation: AllocationRequest, allocatedEquipments: List<EquipmentResponse>) = AllocationResponse(
        id = allocation.id,
        employeeId = allocation.employeeId,
        state = allocation.state,
        policy = allocation.policy.map(::toResponseRequirement),
        allocatedEquipmentIds = allocation.allocatedEquipmentIds,
        allocatedEquipments = allocatedEquipments
    )

    private fun toResponseRequirement(requirement: EquipmentPolicyRequirement) = EquipmentPolicyRequirementRequest(
        type = requirement.type,
        quantity = requirement.quantity,
        minimumConditionScore = requirement.minimumConditionScore,
        preferredBrand = requirement.preferredBrand
    )
}

