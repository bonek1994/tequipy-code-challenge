package com.tequipy.challenge.adapter.spi.persistence.entity

import com.tequipy.challenge.domain.model.EquipmentType

data class EquipmentPolicyRequirementEmbeddable(
    val type: EquipmentType,
    val quantity: Int,
    val minimumConditionScore: Double?,
    val preferredBrand: String?
)
