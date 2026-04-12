package com.tequipy.challenge.adapter.spi.persistence.entity

import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class EquipmentEntity(
    val id: UUID,
    val type: EquipmentType,
    val brand: String,
    val model: String,
    val state: EquipmentState,
    val conditionScore: Double,
    val purchaseDate: LocalDate,
    val retiredReason: String?,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH
)
