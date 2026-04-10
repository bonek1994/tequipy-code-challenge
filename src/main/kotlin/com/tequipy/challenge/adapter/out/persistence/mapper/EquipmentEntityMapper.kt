package com.tequipy.challenge.adapter.out.persistence.mapper

import com.tequipy.challenge.adapter.out.persistence.entity.EquipmentEntity
import com.tequipy.challenge.domain.model.Equipment
import org.springframework.stereotype.Component

@Component
class EquipmentEntityMapper {

    fun toDomain(entity: EquipmentEntity): Equipment = Equipment(
        id = entity.id,
        name = entity.name,
        serialNumber = entity.serialNumber,
        employeeId = entity.employeeId
    )

    fun toEntity(domain: Equipment): EquipmentEntity = EquipmentEntity(
        id = domain.id,
        name = domain.name,
        serialNumber = domain.serialNumber,
        employeeId = domain.employeeId
    )
}
