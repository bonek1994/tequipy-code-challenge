package com.tequipy.challenge.adapter.spi.persistence.mapper

import com.tequipy.challenge.adapter.spi.persistence.entity.EquipmentEntity
import com.tequipy.challenge.domain.model.Equipment
import org.springframework.stereotype.Component

@Component
class EquipmentEntityMapper {

    fun toDomain(entity: EquipmentEntity): Equipment = Equipment(
        id = entity.id,
        type = entity.type,
        brand = entity.brand,
        model = entity.model,
        state = entity.state,
        conditionScore = entity.conditionScore,
        purchaseDate = entity.purchaseDate,
        retiredReason = entity.retiredReason
    )

    fun toEntity(domain: Equipment): EquipmentEntity = EquipmentEntity(
        id = domain.id,
        type = domain.type,
        brand = domain.brand,
        model = domain.model,
        state = domain.state,
        conditionScore = domain.conditionScore,
        purchaseDate = domain.purchaseDate,
        retiredReason = domain.retiredReason
    )
}
