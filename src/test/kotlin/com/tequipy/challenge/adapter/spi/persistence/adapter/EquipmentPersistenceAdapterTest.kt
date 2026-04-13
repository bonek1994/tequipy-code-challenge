package com.tequipy.challenge.adapter.spi.persistence.adapter

import com.tequipy.challenge.adapter.spi.persistence.entity.EquipmentEntity
import com.tequipy.challenge.adapter.spi.persistence.mapper.EquipmentEntityMapper
import com.tequipy.challenge.adapter.spi.persistence.repository.EquipmentJdbcRepository
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class EquipmentPersistenceAdapterTest {

    private val jdbcRepository: EquipmentJdbcRepository = mockk()
    private val mapper: EquipmentEntityMapper = mockk()
    private val adapter = EquipmentPersistenceAdapter(jdbcRepository, mapper)

    private val domainEquipment = Equipment(
        id = UUID.randomUUID(),
        type = EquipmentType.MAIN_COMPUTER,
        brand = "Apple",
        model = "MacBook Pro 14",
        state = EquipmentState.AVAILABLE,
        conditionScore = 0.95,
        purchaseDate = LocalDate.of(2024, 1, 15)
    )

    private val entityEquipment = EquipmentEntity(
        id = domainEquipment.id,
        type = EquipmentType.MAIN_COMPUTER,
        brand = "Apple",
        model = "MacBook Pro 14",
        state = EquipmentState.AVAILABLE,
        conditionScore = 0.95,
        purchaseDate = LocalDate.of(2024, 1, 15),
        retiredReason = null
    )

    @Test
    fun `save delegates to repository and maps result`() {
        every { mapper.toEntity(domainEquipment) } returns entityEquipment
        every { jdbcRepository.save(entityEquipment) } returns entityEquipment
        every { mapper.toDomain(entityEquipment) } returns domainEquipment

        val result = adapter.save(domainEquipment)

        assertEquals(domainEquipment, result)
        verify { jdbcRepository.save(entityEquipment) }
    }

    @Test
    fun `saveAll delegates to repository and maps results`() {
        every { mapper.toEntity(domainEquipment) } returns entityEquipment
        every { jdbcRepository.saveAll(listOf(entityEquipment)) } returns listOf(entityEquipment)
        every { mapper.toDomain(entityEquipment) } returns domainEquipment

        val result = adapter.saveAll(listOf(domainEquipment))

        assertEquals(1, result.size)
        assertEquals(domainEquipment, result[0])
    }

    @Test
    fun `findById returns mapped domain when found`() {
        every { jdbcRepository.findById(domainEquipment.id) } returns entityEquipment
        every { mapper.toDomain(entityEquipment) } returns domainEquipment

        val result = adapter.findById(domainEquipment.id)

        assertEquals(domainEquipment, result)
    }

    @Test
    fun `findById returns null when not found`() {
        every { jdbcRepository.findById(any()) } returns null

        val result = adapter.findById(UUID.randomUUID())

        assertNull(result)
    }

    @Test
    fun `findAll returns all mapped equipment`() {
        every { jdbcRepository.findAll() } returns listOf(entityEquipment)
        every { mapper.toDomain(entityEquipment) } returns domainEquipment

        val result = adapter.findAll()

        assertEquals(1, result.size)
        assertEquals(domainEquipment, result[0])
    }

    @Test
    fun `findByIds returns mapped equipment for given ids`() {
        val ids = listOf(domainEquipment.id)
        every { jdbcRepository.findAllById(ids) } returns listOf(entityEquipment)
        every { mapper.toDomain(entityEquipment) } returns domainEquipment

        val result = adapter.findByIds(ids)

        assertEquals(1, result.size)
    }

    @Test
    fun `findByState returns filtered mapped equipment`() {
        every { jdbcRepository.findByState(EquipmentState.AVAILABLE) } returns listOf(entityEquipment)
        every { mapper.toDomain(entityEquipment) } returns domainEquipment

        val result = adapter.findByState(EquipmentState.AVAILABLE)

        assertEquals(1, result.size)
        assertEquals(EquipmentState.AVAILABLE, result[0].state)
    }

    @Test
    fun `findAvailableWithMinConditionScore delegates correctly`() {
        val types = setOf(EquipmentType.MAIN_COMPUTER)
        every { jdbcRepository.findAvailableWithMinConditionScore(types, 0.7) } returns listOf(entityEquipment)
        every { mapper.toDomain(entityEquipment) } returns domainEquipment

        val result = adapter.findAvailableWithMinConditionScore(types, 0.7)

        assertEquals(1, result.size)
    }

    @Test
    fun `findByIdsForUpdate delegates correctly`() {
        val ids = listOf(domainEquipment.id)
        every { jdbcRepository.findByIdsForUpdate(ids, 0.5) } returns listOf(entityEquipment)
        every { mapper.toDomain(entityEquipment) } returns domainEquipment

        val result = adapter.findByIdsForUpdate(ids, 0.5)

        assertEquals(1, result.size)
    }

    @Test
    fun `existsById returns true when exists`() {
        every { jdbcRepository.existsById(domainEquipment.id) } returns true

        assertTrue(adapter.existsById(domainEquipment.id))
    }

    @Test
    fun `existsById returns false when not exists`() {
        every { jdbcRepository.existsById(any()) } returns false

        assertFalse(adapter.existsById(UUID.randomUUID()))
    }
}

