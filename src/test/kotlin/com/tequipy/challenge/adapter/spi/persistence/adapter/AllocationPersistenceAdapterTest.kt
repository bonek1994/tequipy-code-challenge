package com.tequipy.challenge.adapter.spi.persistence.adapter

import com.tequipy.challenge.adapter.spi.persistence.entity.AllocationEntity as AllocationRowEntity
import com.tequipy.challenge.adapter.spi.persistence.mapper.AllocationEntityMapper
import com.tequipy.challenge.adapter.spi.persistence.repository.AllocationJdbcRepository
import com.tequipy.challenge.domain.model.AllocationEntity as AllocationDomainEntity
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class AllocationPersistenceAdapterTest {

    private val jdbcRepository: AllocationJdbcRepository = mockk()
    private val mapper: AllocationEntityMapper = mockk()
    private val adapter = AllocationPersistenceAdapter(jdbcRepository, mapper)

    private val domainEntity = AllocationDomainEntity(
        id = UUID.randomUUID(),
        state = AllocationState.PENDING,
        policy = listOf(EquipmentPolicyRequirement(EquipmentType.MONITOR, quantity = 1)),
        allocatedEquipmentIds = emptyList()
    )

    private val rowEntity = AllocationRowEntity(
        id = domainEntity.id,
        state = AllocationState.PENDING,
        policy = emptyList(),
        allocatedEquipmentIds = emptyList()
    )

    @Test
    fun `save delegates to repository and maps result`() {
        every { mapper.toEntity(domainEntity) } returns rowEntity
        every { jdbcRepository.save(rowEntity) } returns rowEntity
        every { mapper.toDomain(rowEntity) } returns domainEntity

        val result = adapter.save(domainEntity)

        assertEquals(domainEntity, result)
        verify { jdbcRepository.save(rowEntity) }
    }

    @Test
    fun `findById returns mapped domain entity when found`() {
        every { jdbcRepository.findById(domainEntity.id) } returns rowEntity
        every { mapper.toDomain(rowEntity) } returns domainEntity

        val result = adapter.findById(domainEntity.id)

        assertEquals(domainEntity, result)
    }

    @Test
    fun `findById returns null when not found`() {
        every { jdbcRepository.findById(any()) } returns null

        val result = adapter.findById(UUID.randomUUID())

        assertNull(result)
    }

    @Test
    fun `findByIdempotencyKey returns mapped domain entity when found`() {
        val key = UUID.randomUUID()
        every { jdbcRepository.findByIdempotencyKey(key) } returns rowEntity
        every { mapper.toDomain(rowEntity) } returns domainEntity

        val result = adapter.findByIdempotencyKey(key)

        assertEquals(domainEntity, result)
    }

    @Test
    fun `findByIdempotencyKey returns null when not found`() {
        every { jdbcRepository.findByIdempotencyKey(any()) } returns null

        val result = adapter.findByIdempotencyKey(UUID.randomUUID())

        assertNull(result)
    }

    @Test
    fun `completePending returns mapped domain entity on success`() {
        val equipmentIds = listOf(UUID.randomUUID())
        val updatedRow = rowEntity.copy(state = AllocationState.ALLOCATED, allocatedEquipmentIds = emptyList())
        val updatedDomain = domainEntity.copy(state = AllocationState.ALLOCATED, allocatedEquipmentIds = equipmentIds)

        every { jdbcRepository.completePending(domainEntity.id, AllocationState.ALLOCATED, equipmentIds) } returns updatedRow
        every { mapper.toDomain(updatedRow) } returns updatedDomain

        val result = adapter.completePending(domainEntity.id, AllocationState.ALLOCATED, equipmentIds)

        assertEquals(updatedDomain, result)
    }

    @Test
    fun `completePending returns null when allocation is not pending`() {
        every { jdbcRepository.completePending(any(), any(), any()) } returns null

        val result = adapter.completePending(UUID.randomUUID(), AllocationState.ALLOCATED, emptyList())

        assertNull(result)
    }
}

