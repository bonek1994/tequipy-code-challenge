package com.tequipy.challenge.adapter.out.persistence.adapter

import com.tequipy.challenge.adapter.out.persistence.mapper.EquipmentEntityMapper
import com.tequipy.challenge.adapter.out.persistence.repository.EquipmentJpaRepository
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.out.EquipmentRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class EquipmentPersistenceAdapter(
    private val jpaRepository: EquipmentJpaRepository,
    private val mapper: EquipmentEntityMapper
) : EquipmentRepository {

    override fun save(equipment: Equipment): Equipment {
        val entity = mapper.toEntity(equipment)
        val saved = jpaRepository.save(entity)
        return mapper.toDomain(saved)
    }

    override fun saveAll(equipment: List<Equipment>): List<Equipment> {
        return jpaRepository.saveAll(equipment.map(mapper::toEntity)).map(mapper::toDomain)
    }

    override fun findById(id: UUID): Equipment? {
        return jpaRepository.findById(id).orElse(null)?.let { mapper.toDomain(it) }
    }

    override fun findAll(): List<Equipment> {
        return jpaRepository.findAll().map { mapper.toDomain(it) }
    }

    override fun findByIds(ids: List<UUID>): List<Equipment> {
        return jpaRepository.findAllById(ids).map(mapper::toDomain)
    }

    override fun findByState(state: EquipmentState): List<Equipment> {
        return jpaRepository.findByState(state).map(mapper::toDomain)
    }

    override fun existsById(id: UUID): Boolean {
        return jpaRepository.existsById(id)
    }
}
