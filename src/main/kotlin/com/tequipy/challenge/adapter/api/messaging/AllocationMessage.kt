package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.domain.model.EquipmentType
import java.util.UUID

data class AllocationMessage(
    val id: UUID,
    val policy: List<PolicyRequirementMessage>
) {
    data class PolicyRequirementMessage(
        val type: EquipmentType,
        val quantity: Int,
        val minimumConditionScore: Double? = null,
        val preferredBrand: String? = null
    )
}
