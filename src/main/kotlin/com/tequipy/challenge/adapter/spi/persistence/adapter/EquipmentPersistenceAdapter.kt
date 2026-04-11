package com.tequipy.challenge.adapter.spi.persistence.adapter

import com.tequipy.challenge.adapter.spi.persistence.mapper.EquipmentEntityMapper
import com.tequipy.challenge.adapter.spi.persistence.repository.EquipmentJdbcRepository
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.out.EquipmentRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class EquipmentPersistenceAdapter(
    private val jdbcRepository: EquipmentJdbcRepository,
    private val mapper: EquipmentEntityMapper
) : EquipmentRepository {

    override fun save(equipment: Equipment): Equipment {
        val entity = mapper.toEntity(equipment)
        val saved = jdbcRepository.save(entity)
        return mapper.toDomain(saved)
    }

    override fun saveAll(equipment: List<Equipment>): List<Equipment> {
        return jdbcRepository.saveAll(equipment.map(mapper::toEntity)).map(mapper::toDomain)
    }

    override fun findById(id: UUID): Equipment? {
        return jdbcRepository.findById(id)?.let { mapper.toDomain(it) }
    }

    override fun findAll(): List<Equipment> {
        return jdbcRepository.findAll().map { mapper.toDomain(it) }
    }

    override fun findByIds(ids: List<UUID>): List<Equipment> {
        return jdbcRepository.findAllById(ids).map(mapper::toDomain)
    }

    override fun findByState(state: EquipmentState): List<Equipment> {
        return jdbcRepository.findByState(state).map(mapper::toDomain)
    }

    override fun findAvailableWithMinConditionScore(types: Set<EquipmentType>, minScore: Double): List<Equipment> {
        return jdbcRepository.findAvailableWithMinConditionScore(types, minScore).map(mapper::toDomain)
    }

    override fun findByIdsForUpdate(ids: List<UUID>, minConditionScore: Double): List<Equipment> {
        return jdbcRepository.findByIdsForUpdate(ids, minConditionScore).map(mapper::toDomain)
    }

    override fun existsById(id: UUID): Boolean {
        return jdbcRepository.existsById(id)
    }
}
