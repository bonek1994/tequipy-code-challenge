package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.domain.model.EquipmentType
import java.time.Instant
import java.util.UUID

data class AllocationRequestedMessage(
    val id: UUID,
    val policy: List<PolicyRequirementMessage>,
    val timestamp: Instant = Instant.EPOCH
) {
    data class PolicyRequirementMessage(
        val type: EquipmentType,
        val quantity: Int,
        val minimumConditionScore: Double? = null,
        val preferredBrand: String? = null
    )
}

data class AllocationProcessedMessage(
    val id: UUID,
    val success: Boolean,
    val allocatedEquipmentIds: List<UUID> = emptyList(),
    val timestamp: Instant = Instant.EPOCH
)

