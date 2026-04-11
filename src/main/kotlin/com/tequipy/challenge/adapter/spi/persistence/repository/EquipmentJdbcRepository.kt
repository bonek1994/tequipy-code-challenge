package com.tequipy.challenge.adapter.spi.persistence.repository

import com.tequipy.challenge.adapter.spi.persistence.entity.EquipmentEntity
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.Date
import java.util.UUID

@Repository
class EquipmentJdbcRepository(private val jdbcTemplate: JdbcTemplate) {

    private val rowMapper = RowMapper<EquipmentEntity> { rs, _ ->
        EquipmentEntity(
            id = rs.getObject("id", UUID::class.java),
            type = EquipmentType.valueOf(rs.getString("type")),
            brand = rs.getString("brand"),
            model = rs.getString("model"),
            state = EquipmentState.valueOf(rs.getString("state")),
            conditionScore = rs.getDouble("condition_score"),
            purchaseDate = rs.getDate("purchase_date").toLocalDate(),
            retiredReason = rs.getString("retired_reason")
        )
    }

    fun save(entity: EquipmentEntity): EquipmentEntity {
        jdbcTemplate.update(
            """
            INSERT INTO equipments (id, type, brand, model, state, condition_score, purchase_date, retired_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                type = EXCLUDED.type,
                brand = EXCLUDED.brand,
                model = EXCLUDED.model,
                state = EXCLUDED.state,
                condition_score = EXCLUDED.condition_score,
                purchase_date = EXCLUDED.purchase_date,
                retired_reason = EXCLUDED.retired_reason
            """.trimIndent(),
            entity.id, entity.type.name, entity.brand, entity.model, entity.state.name,
            entity.conditionScore, Date.valueOf(entity.purchaseDate), entity.retiredReason
        )
        return entity
    }

    fun saveAll(entities: List<EquipmentEntity>): List<EquipmentEntity> {
        entities.forEach { save(it) }
        return entities
    }

    fun findById(id: UUID): EquipmentEntity? {
        return try {
            jdbcTemplate.queryForObject("SELECT * FROM equipments WHERE id = ?", rowMapper, id)
        } catch (e: EmptyResultDataAccessException) {
            null
        }
    }

    fun findAll(): List<EquipmentEntity> {
        return jdbcTemplate.query("SELECT * FROM equipments", rowMapper)
    }

    fun findAllById(ids: List<UUID>): List<EquipmentEntity> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return jdbcTemplate.query(
            "SELECT * FROM equipments WHERE id IN ($placeholders)",
            rowMapper,
            *ids.toTypedArray()
        )
    }

    fun findByState(state: EquipmentState): List<EquipmentEntity> {
        return jdbcTemplate.query("SELECT * FROM equipments WHERE state = ?", rowMapper, state.name)
    }

    fun findAvailableWithMinConditionScore(minScore: Double): List<EquipmentEntity> {
        return jdbcTemplate.query(
            "SELECT * FROM equipments WHERE state = ? AND condition_score >= ?",
            rowMapper,
            EquipmentState.AVAILABLE.name, minScore
        )
    }

    fun findByIdsForUpdate(ids: List<UUID>, minConditionScore: Double = 0.0): List<EquipmentEntity> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return jdbcTemplate.query(
            "SELECT * FROM equipments WHERE id IN ($placeholders) AND state = ? AND condition_score >= ? FOR UPDATE SKIP LOCKED",
            rowMapper,
            *ids.toTypedArray(), EquipmentState.AVAILABLE.name, minConditionScore
        )
    }

    fun existsById(id: UUID): Boolean {
        val count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM equipments WHERE id = ?",
            Long::class.java,
            id
        )
        return (count ?: 0L) > 0L
    }
}
