package com.tequipy.challenge.domain.port.`in`

import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import java.util.UUID

interface AllocationUseCase {
    fun createAllocation(policy: List<EquipmentPolicyRequirement>): AllocationRequest
    fun getAllocation(id: UUID): AllocationRequest
    fun confirmAllocation(id: UUID): AllocationRequest
    fun cancelAllocation(id: UUID): AllocationRequest
}
