package com.tequipy.challenge.domain.model

data class EquipmentPolicyRequirement(
	val type: EquipmentType,
	val quantity: Int = 1,
	val minimumConditionScore: Double? = null,
	val preferredBrand: String? = null
)

