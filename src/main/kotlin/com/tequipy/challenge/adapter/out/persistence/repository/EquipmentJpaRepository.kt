package com.tequipy.challenge.adapter.out.persistence.repository

import com.tequipy.challenge.adapter.out.persistence.entity.EquipmentEntity
import com.tequipy.challenge.domain.model.EquipmentState
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EquipmentJpaRepository : JpaRepository<EquipmentEntity, UUID> {
    fun findByState(state: EquipmentState): List<EquipmentEntity>
}
