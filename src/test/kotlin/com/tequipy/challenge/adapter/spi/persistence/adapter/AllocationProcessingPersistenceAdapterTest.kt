package com.tequipy.challenge.adapter.spi.persistence.adapter

import com.tequipy.challenge.adapter.spi.persistence.repository.AllocationProcessingJdbcRepository
import com.tequipy.challenge.domain.model.AllocationProcessingRecord
import com.tequipy.challenge.domain.model.AllocationProcessingState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.UUID

class AllocationProcessingPersistenceAdapterTest {

    private val jdbcRepository: AllocationProcessingJdbcRepository = mockk()
    private val adapter = AllocationProcessingPersistenceAdapter(jdbcRepository)

    @Test
    fun `tryStart returns true when insert succeeds`() {
        val allocationId = UUID.randomUUID()
        every { jdbcRepository.tryStart(allocationId) } returns true

        assertTrue(adapter.tryStart(allocationId))
        verify { jdbcRepository.tryStart(allocationId) }
    }

    @Test
    fun `tryStart returns false when allocation already exists`() {
        val allocationId = UUID.randomUUID()
        every { jdbcRepository.tryStart(allocationId) } returns false

        assertFalse(adapter.tryStart(allocationId))
    }

    @Test
    fun `findById returns record when found`() {
        val allocationId = UUID.randomUUID()
        val equipmentId = UUID.randomUUID()
        val record = AllocationProcessingRecord(
            allocationId = allocationId,
            state = AllocationProcessingState.ALLOCATED,
            allocatedEquipmentIds = listOf(equipmentId)
        )
        every { jdbcRepository.findById(allocationId) } returns record

        val result = adapter.findById(allocationId)

        assertEquals(record, result)
    }

    @Test
    fun `findById returns null when not found`() {
        every { jdbcRepository.findById(any()) } returns null

        val result = adapter.findById(UUID.randomUUID())

        assertNull(result)
    }

    @Test
    fun `complete delegates to repository and returns record`() {
        val allocationId = UUID.randomUUID()
        val equipmentIds = listOf(UUID.randomUUID())
        val record = AllocationProcessingRecord(
            allocationId = allocationId,
            state = AllocationProcessingState.ALLOCATED,
            allocatedEquipmentIds = equipmentIds
        )
        every {
            jdbcRepository.complete(allocationId, AllocationProcessingState.ALLOCATED, equipmentIds)
        } returns record

        val result = adapter.complete(allocationId, AllocationProcessingState.ALLOCATED, equipmentIds)

        assertEquals(record, result)
        verify { jdbcRepository.complete(allocationId, AllocationProcessingState.ALLOCATED, equipmentIds) }
    }

    @Test
    fun `complete with FAILED state and empty equipment ids`() {
        val allocationId = UUID.randomUUID()
        val record = AllocationProcessingRecord(
            allocationId = allocationId,
            state = AllocationProcessingState.FAILED,
            allocatedEquipmentIds = emptyList()
        )
        every {
            jdbcRepository.complete(allocationId, AllocationProcessingState.FAILED, emptyList())
        } returns record

        val result = adapter.complete(allocationId, AllocationProcessingState.FAILED, emptyList())

        assertEquals(AllocationProcessingState.FAILED, result.state)
        assertTrue(result.allocatedEquipmentIds.isEmpty())
    }
}

