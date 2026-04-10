package com.tequipy.challenge.adapter.out.persistence.entity

import com.tequipy.challenge.domain.model.EquipmentType
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated

@Embeddable
data class EquipmentPolicyRequirementEmbeddable(
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    val type: EquipmentType,

    @Column(name = "quantity", nullable = false)
    val quantity: Int,

    @Column(name = "minimum_condition_score")
    val minimumConditionScore: Double?,

    @Column(name = "preferred_brand")
    val preferredBrand: String?
)

