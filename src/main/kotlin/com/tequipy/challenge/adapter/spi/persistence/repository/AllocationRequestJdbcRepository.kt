package com.tequipy.challenge.adapter.spi.persistence.repository

import com.tequipy.challenge.adapter.spi.persistence.entity.AllocationRequestEntity
import com.tequipy.challenge.adapter.spi.persistence.entity.EquipmentPolicyRequirementEmbeddable
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentType
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AllocationRequestJdbcRepository(private val jdbcTemplate: JdbcTemplate) {

    private val allocationRowMapper = RowMapper<AllocationRequestEntity> { rs, _ ->
        AllocationRequestEntity(
            id = rs.getObject("id", UUID::class.java),
            state = AllocationState.valueOf(rs.getString("state")),
            policy = emptyList(),
            allocatedEquipmentIds = emptyList(),
            idempotencyKey = rs.getObject("idempotency_key", UUID::class.java)
        )
    }

    private val policyRowMapper = RowMapper<EquipmentPolicyRequirementEmbeddable> { rs, _ ->
        val rawScore = rs.getDouble("minimum_condition_score")
        EquipmentPolicyRequirementEmbeddable(
            type = EquipmentType.valueOf(rs.getString("type")),
            quantity = rs.getInt("quantity"),
            minimumConditionScore = if (rs.wasNull()) null else rawScore,
            preferredBrand = rs.getString("preferred_brand")
        )
    }

    fun save(entity: AllocationRequestEntity): AllocationRequestEntity {
        jdbcTemplate.update(
            """
            INSERT INTO allocation_requests (id, state, idempotency_key)
            VALUES (?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                state = EXCLUDED.state
            """.trimIndent(),
            entity.id, entity.state.name, entity.idempotencyKey
        )

        jdbcTemplate.update(
            "DELETE FROM allocation_policy_requirements WHERE allocation_request_id = ?",
            entity.id
        )
        entity.policy.forEach { req ->
            jdbcTemplate.update(
                """
                INSERT INTO allocation_policy_requirements
                    (allocation_request_id, type, quantity, minimum_condition_score, preferred_brand)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                entity.id, req.type.name, req.quantity, req.minimumConditionScore, req.preferredBrand
            )
        }

        jdbcTemplate.update(
            "DELETE FROM allocation_equipment_ids WHERE allocation_request_id = ?",
            entity.id
        )
        entity.allocatedEquipmentIds.forEach { equipmentId ->
            jdbcTemplate.update(
                "INSERT INTO allocation_equipment_ids (allocation_request_id, equipment_id) VALUES (?, ?)",
                entity.id, equipmentId
            )
        }

        return entity
    }

    fun findById(id: UUID): AllocationRequestEntity? {
        val allocation = try {
            jdbcTemplate.queryForObject(
                "SELECT * FROM allocation_requests WHERE id = ?",
                allocationRowMapper,
                id
            )
        } catch (e: EmptyResultDataAccessException) {
            null
        } ?: return null

        val policy = jdbcTemplate.query(
            "SELECT * FROM allocation_policy_requirements WHERE allocation_request_id = ?",
            policyRowMapper,
            id
        )

        val equipmentIds = jdbcTemplate.query(
            "SELECT equipment_id FROM allocation_equipment_ids WHERE allocation_request_id = ?",
            { rs, _ -> rs.getObject("equipment_id", UUID::class.java) },
            id
        )

        return allocation.copy(policy = policy, allocatedEquipmentIds = equipmentIds)
    }

    fun findByIdempotencyKey(key: UUID): AllocationRequestEntity? {
        val allocation = try {
            jdbcTemplate.queryForObject(
                "SELECT * FROM allocation_requests WHERE idempotency_key = ?",
                allocationRowMapper,
                key
            )
        } catch (e: EmptyResultDataAccessException) {
            null
        } ?: return null

        val policy = jdbcTemplate.query(
            "SELECT * FROM allocation_policy_requirements WHERE allocation_request_id = ?",
            policyRowMapper,
            allocation.id
        )

        val equipmentIds = jdbcTemplate.query(
            "SELECT equipment_id FROM allocation_equipment_ids WHERE allocation_request_id = ?",
            { rs, _ -> rs.getObject("equipment_id", UUID::class.java) },
            allocation.id
        )

        return allocation.copy(policy = policy, allocatedEquipmentIds = equipmentIds)
    }
}
