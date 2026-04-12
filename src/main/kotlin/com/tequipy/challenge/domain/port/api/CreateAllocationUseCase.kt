package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement

interface CreateAllocationUseCase {
    fun createAllocation(policy: List<EquipmentPolicyRequirement>): AllocationRequest
}

