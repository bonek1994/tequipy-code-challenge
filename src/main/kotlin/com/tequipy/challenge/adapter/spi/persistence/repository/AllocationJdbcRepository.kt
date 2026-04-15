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
        savePolicy(entity.id, entity.policy)
        saveEquipmentIds(entity.id, entity.allocatedEquipmentIds)
        return findById(entity.id) ?: entity
    }

    fun findById(id: UUID): AllocationEntity? =
        queryAllocation("SELECT * FROM allocations WHERE id = ?", id)

    fun findByIdempotencyKey(key: UUID): AllocationEntity? =
        queryAllocation("SELECT * FROM allocations WHERE idempotency_key = ?", key)

    fun completePending(id: UUID, state: AllocationState, allocatedEquipmentIds: List<UUID>): AllocationEntity? {
        val updated = jdbcTemplate.update(
            "UPDATE allocations SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = ?",
            state.name, id, AllocationState.PENDING.name
        )
        if (updated == 0) return null
        saveEquipmentIds(id, allocatedEquipmentIds)
        return findById(id)
    }

    fun completePendingBatch(completions: List<AllocationCompletion>): List<AllocationEntity> {
        if (completions.isEmpty()) return emptyList()

        val updatedRows = jdbcTemplate.batchUpdate(
            "UPDATE allocations SET state = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND state = ?",
            completions.map { arrayOf(it.state.name, it.allocationId, AllocationState.PENDING.name) }
        )

        val applied = completions.zip(updatedRows.toList())
            .filter { (_, rows) -> rows > 0 }
            .map { (completion, _) -> completion }

        if (applied.isEmpty()) return emptyList()

        jdbcTemplate.batchUpdate(
            "DELETE FROM allocation_equipment_ids WHERE allocation_request_id = ?",
            applied.map { arrayOf(it.allocationId) }
        )

        val equipmentRows = applied.flatMap { c ->
            c.allocatedEquipmentIds.map { eqId -> arrayOf(c.allocationId, eqId) }
        }
        if (equipmentRows.isNotEmpty()) {
            jdbcTemplate.batchUpdate(
                "INSERT INTO allocation_equipment_ids (allocation_request_id, equipment_id) VALUES (?, ?)",
                equipmentRows
            )
        }

        return applied.mapNotNull { findById(it.allocationId) }
    }

    // ── Shared helpers ─────────────────────────────────────────────────────

    private fun queryAllocation(sql: String, param: Any): AllocationEntity? {
        val allocation = try {
            jdbcTemplate.queryForObject(sql, allocationRowMapper, param)
        } catch (_: EmptyResultDataAccessException) {
            null
        } ?: return null

        return allocation.copy(
            policy = loadPolicy(allocation.id),
            allocatedEquipmentIds = loadEquipmentIds(allocation.id)
        )
    }

    private fun loadPolicy(allocationId: UUID): List<EquipmentPolicyRequirementEmbeddable> =
        jdbcTemplate.query(
            "SELECT * FROM allocation_policy_requirements WHERE allocation_request_id = ?",
            policyRowMapper, allocationId
        )

    private fun loadEquipmentIds(allocationId: UUID): List<UUID> =
        jdbcTemplate.query(
            "SELECT equipment_id FROM allocation_equipment_ids WHERE allocation_request_id = ?",
            { rs, _ -> rs.getObject("equipment_id", UUID::class.java) },
            allocationId
        )

    private fun savePolicy(allocationId: UUID, policy: List<EquipmentPolicyRequirementEmbeddable>) {
        jdbcTemplate.update("DELETE FROM allocation_policy_requirements WHERE allocation_request_id = ?", allocationId)
        policy.forEach { req ->
            jdbcTemplate.update(
                """
                INSERT INTO allocation_policy_requirements
                    (allocation_request_id, type, quantity, minimum_condition_score, preferred_brand)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                allocationId, req.type.name, req.quantity, req.minimumConditionScore, req.preferredBrand
            )
        }
    }

    private fun saveEquipmentIds(allocationId: UUID, equipmentIds: List<UUID>) {
        jdbcTemplate.update("DELETE FROM allocation_equipment_ids WHERE allocation_request_id = ?", allocationId)
        equipmentIds.forEach { eqId ->
            jdbcTemplate.update(
                "INSERT INTO allocation_equipment_ids (allocation_request_id, equipment_id) VALUES (?, ?)",
                allocationId, eqId
            )
        }
    }
}
