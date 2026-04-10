package com.tequipy.challenge.adapter.`in`.web.mapper

import com.tequipy.challenge.adapter.`in`.web.dto.EquipmentResponse
import com.tequipy.challenge.domain.model.Equipment
import org.springframework.stereotype.Component

@Component
class EquipmentMapper {

    fun toResponse(equipment: Equipment): EquipmentResponse = EquipmentResponse(
        id = equipment.id,
        type = equipment.type,
        brand = equipment.brand,
        model = equipment.model,
        state = equipment.state,
        conditionScore = equipment.conditionScore,
        purchaseDate = equipment.purchaseDate,
        retiredReason = equipment.retiredReason
    )

    fun toResponseList(equipment: List<Equipment>): List<EquipmentResponse> =
        equipment.map { toResponse(it) }
}
