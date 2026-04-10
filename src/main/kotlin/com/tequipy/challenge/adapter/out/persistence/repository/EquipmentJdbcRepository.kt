package com.tequipy.challenge.adapter.out.persistence.repository

import com.tequipy.challenge.adapter.out.persistence.entity.EquipmentEntity
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
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

    /**
     * For each requirement in the policy, builds a ranked sub-query that selects the best
     * available candidates (applying hard constraints and scoring by preferred brand and
     * condition score). All candidate IDs are unioned and then locked in a single
     * SELECT FOR UPDATE SKIP LOCKED, eliminating any non-locking pre-scan.
     *
     * The LIMIT per sub-query is the total quantity demanded for that equipment type,
     * so every competing slot of the same type has enough ranked candidates to choose from.
     */
    fun findAvailableByPolicyForUpdate(policy: List<EquipmentPolicyRequirement>): List<EquipmentEntity> {
        if (policy.isEmpty()) return emptyList()

        // Total quantity per type – used as LIMIT for each per-requirement sub-query so
        // that all competing slots of the same type have sufficient candidates.
        val quantityPerType = policy.groupBy { it.type }
            .mapValues { (_, reqs) -> reqs.sumOf { it.quantity } }

        val subQueryParts = mutableListOf<String>()
        // The outer WHERE state = ? is the first positional parameter; sub-query params follow.
        val params = mutableListOf<Any>(EquipmentState.AVAILABLE.name)

        for (requirement in policy) {
            val hasMinCondition = requirement.minimumConditionScore != null
            val hasPreferredBrand = requirement.preferredBrand != null
            val totalQtyForType = quantityPerType[requirement.type] ?: requirement.quantity

            val conditionClause = if (hasMinCondition) "AND condition_score >= ?" else ""
            val scoreExpr = if (hasPreferredBrand) {
                "CASE WHEN LOWER(brand) = LOWER(?) THEN 10.0 ELSE 0.0 END + condition_score"
            } else {
                "condition_score"
            }

            subQueryParts.add(
                "(SELECT id FROM equipments" +
                    " WHERE state = ? AND type = ? $conditionClause" +
                    " ORDER BY $scoreExpr DESC, purchase_date DESC" +
                    " LIMIT ?)"
            )

            params.add(EquipmentState.AVAILABLE.name)
            params.add(requirement.type.name)
            if (hasMinCondition) params.add(requirement.minimumConditionScore!!)
            if (hasPreferredBrand) params.add(requirement.preferredBrand!!)
            params.add(totalQtyForType)
        }

        val sql =
            "SELECT e.id, e.type, e.brand, e.model, e.state, e.condition_score, e.purchase_date, e.retired_reason" +
                " FROM equipments e" +
                " WHERE e.state = ? AND e.id IN (${subQueryParts.joinToString(" UNION ")})" +
                " FOR UPDATE SKIP LOCKED"

        return jdbcTemplate.query(sql, rowMapper, *params.toTypedArray())
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
