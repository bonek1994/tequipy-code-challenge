package com.tequipy.challenge.adapter.api.messaging.events

import com.tequipy.challenge.domain.model.EquipmentType
import java.time.Instant
import java.util.UUID

data class AllocationCreated(
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