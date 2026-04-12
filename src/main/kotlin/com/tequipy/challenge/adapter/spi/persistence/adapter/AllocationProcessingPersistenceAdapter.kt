package com.tequipy.challenge.adapter.spi.persistence.adapter

import com.tequipy.challenge.adapter.spi.persistence.repository.AllocationProcessingJdbcRepository
import com.tequipy.challenge.domain.model.AllocationProcessingRecord
import com.tequipy.challenge.domain.model.AllocationProcessingState
import com.tequipy.challenge.domain.port.spi.AllocationProcessingRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class AllocationProcessingPersistenceAdapter(
    private val jdbcRepository: AllocationProcessingJdbcRepository
) : AllocationProcessingRepository {
    override fun tryStart(allocationId: UUID): Boolean = jdbcRepository.tryStart(allocationId)

    override fun findById(allocationId: UUID): AllocationProcessingRecord? = jdbcRepository.findById(allocationId)

    override fun complete(
        allocationId: UUID,
        state: AllocationProcessingState,
        allocatedEquipmentIds: List<UUID>
    ): AllocationProcessingRecord = jdbcRepository.complete(allocationId, state, allocatedEquipmentIds)
}

