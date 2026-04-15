package com.tequipy.challenge.adapter.spi.persistence.repository

import com.tequipy.challenge.adapter.spi.persistence.entity.AllocationEntity
import com.tequipy.challenge.adapter.spi.persistence.entity.EquipmentPolicyRequirementEmbeddable
import com.tequipy.challenge.domain.model.AllocationCompletion
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentType
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AllocationJdbcRepository(private val jdbcTemplate: JdbcTemplate) {

    private val allocationRowMapper = RowMapper<AllocationEntity> { rs, _ ->
        AllocationEntity(
            id = rs.getObject("id", UUID::class.java),
            state = AllocationState.valueOf(rs.getString("state")),
            policy = emptyList(),
            allocatedEquipmentIds = emptyList(),
            idempotencyKey = rs.getObject("idempotency_key", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
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

    fun save(entity: AllocationEntity): AllocationEntity {
        jdbcTemplate.update(
            """
            INSERT INTO allocations (id, state, idempotency_key)
            VALUES (?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                state = EXCLUDED.state,
                updated_at = CURRENT_TIMESTAMP
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

        return findById(entity.id) ?: entity
    }

    fun findById(id: UUID): AllocationEntity? {
        val allocation = try {
            jdbcTemplate.queryForObject(
                "SELECT * FROM allocations WHERE id = ?",
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

    fun findByIdempotencyKey(key: UUID): AllocationEntity? {
        val allocation = try {
            jdbcTemplate.queryForObject(
                "SELECT * FROM allocations WHERE idempotency_key = ?",
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

    fun completePending(id: UUID, state: AllocationState, allocatedEquipmentIds: List<UUID>): AllocationEntity? {
        val updatedRows = jdbcTemplate.update(
            "UPDATE allocations SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = ?",
            state.name,
            id,
            AllocationState.PENDING.name
        )
        if (updatedRows == 0) {
            return null
        }

        jdbcTemplate.update(
            "DELETE FROM allocation_equipment_ids WHERE allocation_request_id = ?",
            id
        )
        allocatedEquipmentIds.forEach { equipmentId ->
            jdbcTemplate.update(
                "INSERT INTO allocation_equipment_ids (allocation_request_id, equipment_id) VALUES (?, ?)",
                id,
                equipmentId
            )
        }

        return findById(id)
    }

    fun completePendingBatch(completions: List<AllocationCompletion>): List<AllocationEntity> {
        if (completions.isEmpty()) return emptyList()

        val updatedRows = jdbcTemplate.batchUpdate(
            "UPDATE allocations SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = ?",
            completions.map { completion ->
                arrayOf(
                    completion.state.name,
                    completion.allocationId,
                    AllocationState.PENDING.name
                )
            }
        )

        val appliedCompletions = completions.zip(updatedRows.toList())
            .filter { (_, updated) -> updated > 0 }
            .map { (completion, _) -> completion }

        if (appliedCompletions.isEmpty()) {
            return emptyList()
        }

        jdbcTemplate.batchUpdate(
            "DELETE FROM allocation_equipment_ids WHERE allocation_request_id = ?",
            appliedCompletions.map { completion -> arrayOf(completion.allocationId) }
        )

        val equipmentRows = appliedCompletions.flatMap { completion ->
            completion.allocatedEquipmentIds.map { equipmentId ->
                arrayOf(completion.allocationId, equipmentId)
            }
        }

        if (equipmentRows.isNotEmpty()) {
            jdbcTemplate.batchUpdate(
                "INSERT INTO allocation_equipment_ids (allocation_request_id, equipment_id) VALUES (?, ?)",
                equipmentRows
            )
        }

        return appliedCompletions.mapNotNull { completion -> findById(completion.allocationId) }
    }
}


