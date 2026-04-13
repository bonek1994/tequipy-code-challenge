package com.tequipy.challenge.adapter.spi.persistence.repository

import com.tequipy.challenge.domain.model.AllocationProcessingRecord
import com.tequipy.challenge.domain.model.AllocationProcessingState
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AllocationProcessingJdbcRepository(
    private val jdbcTemplate: JdbcTemplate
) {
    fun tryStart(allocationId: UUID): Boolean {
        val updatedRows = jdbcTemplate.update(
            "INSERT INTO allocation_processing_results (allocation_id, state, created_at, updated_at) VALUES (?, ?, clock_timestamp(), clock_timestamp()) ON CONFLICT (allocation_id) DO NOTHING",
            allocationId,
            AllocationProcessingState.PROCESSING.name
        )
        return updatedRows == 1
    }

    fun findById(allocationId: UUID): AllocationProcessingRecord? {
        val state = jdbcTemplate.query(
            "SELECT state FROM allocation_processing_results WHERE allocation_id = ?",
            { rs, _ -> AllocationProcessingState.valueOf(rs.getString("state")) },
            allocationId
        ).singleOrNull() ?: return null

        val equipmentIds = jdbcTemplate.query(
            "SELECT equipment_id FROM allocation_processing_equipment_ids WHERE allocation_id = ?",
            { rs, _ -> rs.getObject("equipment_id", UUID::class.java) },
            allocationId
        )

        return AllocationProcessingRecord(
            allocationId = allocationId,
            state = state,
            allocatedEquipmentIds = equipmentIds
        )
    }

    fun complete(
        allocationId: UUID,
        state: AllocationProcessingState,
        allocatedEquipmentIds: List<UUID>
    ): AllocationProcessingRecord {
        jdbcTemplate.update(
            "UPDATE allocation_processing_results SET state = ?, updated_at = clock_timestamp() WHERE allocation_id = ?",
            state.name,
            allocationId
        )
        jdbcTemplate.update(
            "DELETE FROM allocation_processing_equipment_ids WHERE allocation_id = ?",
            allocationId
        )
        allocatedEquipmentIds.forEach { equipmentId ->
            jdbcTemplate.update(
                "INSERT INTO allocation_processing_equipment_ids (allocation_id, equipment_id) VALUES (?, ?)",
                allocationId,
                equipmentId
            )
        }

        return AllocationProcessingRecord(
            allocationId = allocationId,
            state = state,
            allocatedEquipmentIds = allocatedEquipmentIds
        )
    }
}

