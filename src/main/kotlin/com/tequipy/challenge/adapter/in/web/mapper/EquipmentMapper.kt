package com.tequipy.challenge.adapter.`in`.web.mapper

import com.tequipy.challenge.adapter.`in`.web.dto.EquipmentResponse
import com.tequipy.challenge.domain.model.Equipment
import org.springframework.stereotype.Component

@Component
class EquipmentMapper {

    fun toResponse(equipment: Equipment): EquipmentResponse = EquipmentResponse(
        id = equipment.id,
        name = equipment.name,
        serialNumber = equipment.serialNumber,
        employeeId = equipment.employeeId
    )

    fun toResponseList(equipment: List<Equipment>): List<EquipmentResponse> =
        equipment.map { toResponse(it) }
}
