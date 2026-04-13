package com.tequipy.challenge.adapter.api.web.mapper

import com.tequipy.challenge.adapter.api.web.dto.EquipmentPolicyRequirementRequest
import com.tequipy.challenge.adapter.api.web.dto.EquipmentResponse
import com.tequipy.challenge.domain.model.AllocationEntity
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class AllocationMapperTest {

    private val mapper = AllocationMapper()

    @Test
    fun `toDomain maps all fields from request to domain requirement`() {
        val request = EquipmentPolicyRequirementRequest(
            type = EquipmentType.MONITOR,
            quantity = 3,
            minimumConditionScore = 0.8,
            preferredBrand = "Dell"
        )

        val domain = mapper.toDomain(request)

        assertEquals(EquipmentType.MONITOR, domain.type)
        assertEquals(3, domain.quantity)
        assertEquals(0.8, domain.minimumConditionScore)
        assertEquals("Dell", domain.preferredBrand)
    }

    @Test
    fun `toDomain maps nullable fields as null`() {
        val request = EquipmentPolicyRequirementRequest(
            type = EquipmentType.KEYBOARD,
            quantity = 1
        )

        val domain = mapper.toDomain(request)

        assertEquals(EquipmentType.KEYBOARD, domain.type)
        assertEquals(1, domain.quantity)
        assertEquals(null, domain.minimumConditionScore)
        assertEquals(null, domain.preferredBrand)
    }

    @Test
    fun `toResponse maps allocation entity and equipments to response`() {
        val allocationId = UUID.randomUUID()
        val equipmentId = UUID.randomUUID()

        val allocation = AllocationEntity(
            id = allocationId,
            state = AllocationState.ALLOCATED,
            policy = listOf(
                EquipmentPolicyRequirement(
                    type = EquipmentType.MAIN_COMPUTER,
                    quantity = 1,
                    minimumConditionScore = 0.7,
                    preferredBrand = "Apple"
                )
            ),
            allocatedEquipmentIds = listOf(equipmentId)
        )

        val equipmentResponse = EquipmentResponse(
            id = equipmentId,
            type = EquipmentType.MAIN_COMPUTER,
            brand = "Apple",
            model = "MacBook Pro 14",
            state = EquipmentState.RESERVED,
            conditionScore = 0.95,
            purchaseDate = LocalDate.of(2024, 1, 15),
            retiredReason = null
        )

        val response = mapper.toResponse(allocation, listOf(equipmentResponse))

        assertEquals(allocationId, response.id)
        assertEquals(AllocationState.ALLOCATED, response.state)
        assertEquals(1, response.policy.size)
        assertEquals(EquipmentType.MAIN_COMPUTER, response.policy[0].type)
        assertEquals(1, response.policy[0].quantity)
        assertEquals(0.7, response.policy[0].minimumConditionScore)
        assertEquals("Apple", response.policy[0].preferredBrand)
        assertEquals(listOf(equipmentId), response.allocatedEquipmentIds)
        assertEquals(1, response.allocatedEquipments.size)
        assertEquals(equipmentId, response.allocatedEquipments[0].id)
    }

    @Test
    fun `toResponse with empty policy and no equipment`() {
        val allocationId = UUID.randomUUID()

        val allocation = AllocationEntity(
            id = allocationId,
            state = AllocationState.PENDING,
            policy = emptyList(),
            allocatedEquipmentIds = emptyList()
        )

        val response = mapper.toResponse(allocation, emptyList())

        assertEquals(allocationId, response.id)
        assertEquals(AllocationState.PENDING, response.state)
        assertEquals(emptyList<EquipmentPolicyRequirementRequest>(), response.policy)
        assertEquals(emptyList<UUID>(), response.allocatedEquipmentIds)
        assertEquals(emptyList<EquipmentResponse>(), response.allocatedEquipments)
    }
}

