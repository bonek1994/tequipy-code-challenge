package com.tequipy.challenge.adapter.spi.persistence.mapper

import com.tequipy.challenge.adapter.spi.persistence.entity.AllocationEntity as AllocationRowEntity
import com.tequipy.challenge.adapter.spi.persistence.entity.EquipmentPolicyRequirementEmbeddable
import com.tequipy.challenge.domain.model.AllocationEntity as AllocationDomainEntity
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AllocationEntityMapperTest {

    private val mapper = AllocationEntityMapper()

    @Test
    fun `toDomain maps all fields from persistence entity to domain entity`() {
        val id = UUID.randomUUID()
        val equipmentId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID()

        val entity = AllocationRowEntity(
            id = id,
            state = AllocationState.ALLOCATED,
            policy = listOf(
                EquipmentPolicyRequirementEmbeddable(
                    type = EquipmentType.MONITOR,
                    quantity = 2,
                    minimumConditionScore = 0.8,
                    preferredBrand = "Dell"
                )
            ),
            allocatedEquipmentIds = listOf(equipmentId),
            idempotencyKey = idempotencyKey,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val domain = mapper.toDomain(entity)

        assertEquals(id, domain.id)
        assertEquals(AllocationState.ALLOCATED, domain.state)
        assertEquals(1, domain.policy.size)
        assertEquals(EquipmentType.MONITOR, domain.policy[0].type)
        assertEquals(2, domain.policy[0].quantity)
        assertEquals(0.8, domain.policy[0].minimumConditionScore)
        assertEquals("Dell", domain.policy[0].preferredBrand)
        assertEquals(listOf(equipmentId), domain.allocatedEquipmentIds)
        assertEquals(idempotencyKey, domain.idempotencyKey)
    }

    @Test
    fun `toDomain maps entity with nullable fields`() {
        val id = UUID.randomUUID()

        val entity = AllocationRowEntity(
            id = id,
            state = AllocationState.PENDING,
            policy = listOf(
                EquipmentPolicyRequirementEmbeddable(
                    type = EquipmentType.KEYBOARD,
                    quantity = 1,
                    minimumConditionScore = null,
                    preferredBrand = null
                )
            ),
            allocatedEquipmentIds = emptyList(),
            idempotencyKey = null
        )

        val domain = mapper.toDomain(entity)

        assertNull(domain.policy[0].minimumConditionScore)
        assertNull(domain.policy[0].preferredBrand)
        assertNull(domain.idempotencyKey)
    }

    @Test
    fun `toEntity maps all fields from domain entity to persistence entity`() {
        val id = UUID.randomUUID()
        val equipmentId = UUID.randomUUID()
        val idempotencyKey = UUID.randomUUID()

        val domain = AllocationDomainEntity(
            id = id,
            state = AllocationState.CONFIRMED,
            policy = listOf(
                EquipmentPolicyRequirement(
                    type = EquipmentType.MAIN_COMPUTER,
                    quantity = 1,
                    minimumConditionScore = 0.7,
                    preferredBrand = "Apple"
                )
            ),
            allocatedEquipmentIds = listOf(equipmentId),
            idempotencyKey = idempotencyKey
        )

        val entity = mapper.toEntity(domain)

        assertEquals(id, entity.id)
        assertEquals(AllocationState.CONFIRMED, entity.state)
        assertEquals(1, entity.policy.size)
        assertEquals(EquipmentType.MAIN_COMPUTER, entity.policy[0].type)
        assertEquals(1, entity.policy[0].quantity)
        assertEquals(0.7, entity.policy[0].minimumConditionScore)
        assertEquals("Apple", entity.policy[0].preferredBrand)
        assertEquals(listOf(equipmentId), entity.allocatedEquipmentIds)
        assertEquals(idempotencyKey, entity.idempotencyKey)
    }

    @Test
    fun `roundtrip domain to entity and back preserves data`() {
        val id = UUID.randomUUID()
        val equipmentId = UUID.randomUUID()

        val original = AllocationDomainEntity(
            id = id,
            state = AllocationState.FAILED,
            policy = listOf(
                EquipmentPolicyRequirement(
                    type = EquipmentType.MOUSE,
                    quantity = 5,
                    minimumConditionScore = 0.5,
                    preferredBrand = "Logitech"
                )
            ),
            allocatedEquipmentIds = listOf(equipmentId),
            idempotencyKey = null
        )

        val roundtripped = mapper.toDomain(mapper.toEntity(original))

        assertEquals(original, roundtripped)
    }
}

