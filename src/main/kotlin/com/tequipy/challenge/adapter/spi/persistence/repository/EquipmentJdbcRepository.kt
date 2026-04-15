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
            retiredReason = rs.getString("retired_reason"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }

    fun insert(entity: EquipmentEntity): EquipmentEntity {
        jdbcTemplate.update(
            """
            INSERT INTO equipments (id, type, brand, model, state, condition_score, purchase_date, retired_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            entity.id, entity.type.name, entity.brand, entity.model, entity.state.name,
            entity.conditionScore, Date.valueOf(entity.purchaseDate), entity.retiredReason
        )
        return findById(entity.id) ?: entity
    }

    fun update(entity: EquipmentEntity): EquipmentEntity {
        jdbcTemplate.update(
            """
            UPDATE equipments SET
                type            = ?,
                brand           = ?,
                model           = ?,
                state           = ?,
                condition_score = ?,
                purchase_date   = ?,
                retired_reason  = ?,
                updated_at      = CURRENT_TIMESTAMP
            WHERE id = ?
            """.trimIndent(),
            entity.type.name, entity.brand, entity.model, entity.state.name,
            entity.conditionScore, Date.valueOf(entity.purchaseDate), entity.retiredReason,
            entity.id
        )
        return findById(entity.id) ?: entity
    }

    fun updateAll(entities: List<EquipmentEntity>): List<EquipmentEntity> {
        return entities.map { update(it) }
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

    fun findAvailableWithMinConditionScore(types: Set<EquipmentType>, minScore: Double): List<EquipmentEntity> {
        if (types.isEmpty()) return emptyList()
        val placeholders = types.joinToString(",") { "?" }
        return jdbcTemplate.query(
            "SELECT * FROM equipments WHERE state = ? AND type IN ($placeholders) AND condition_score >= ? ORDER BY type ASC, condition_score DESC",
            rowMapper,
            EquipmentState.AVAILABLE.name, *types.map { it.name }.toTypedArray(), minScore
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
        return count > 0L
    }
}
