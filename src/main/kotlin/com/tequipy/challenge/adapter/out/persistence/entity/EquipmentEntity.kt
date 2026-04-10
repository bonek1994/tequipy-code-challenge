package com.tequipy.challenge.adapter.out.persistence.entity

import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import jakarta.persistence.*
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "equipments")
class EquipmentEntity(
    @Id
    val id: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: EquipmentType,

    @Column(nullable = false)
    val brand: String,

    @Column(nullable = false)
    val model: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val state: EquipmentState,

    @Column(nullable = false)
    val conditionScore: Double,

    @Column(nullable = false)
    val purchaseDate: LocalDate,

    @Column(nullable = true)
    val retiredReason: String?
)
