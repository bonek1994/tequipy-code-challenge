package com.tequipy.challenge.adapter.spi.messaging

import com.tequipy.challenge.adapter.api.messaging.AllocationProcessedMessage
import com.tequipy.challenge.adapter.api.messaging.AllocationRequestedMessage
import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.model.AllocationEntity
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentType
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.amqp.rabbit.core.RabbitTemplate
import java.util.UUID

class RabbitMQAllocationEventPublisherTest {

    private val rabbitTemplate: RabbitTemplate = mockk(relaxed = true)
    private val publisher = RabbitMQAllocationEventPublisher(rabbitTemplate)

    @Test
    fun `publishAllocationCreated sends message to allocation queue`() {
        val allocationId = UUID.randomUUID()
        val allocation = AllocationEntity(
            id = allocationId,
            state = AllocationState.PENDING,
            policy = listOf(
                EquipmentPolicyRequirement(
                    type = EquipmentType.MONITOR,
                    quantity = 2,
                    minimumConditionScore = 0.8,
                    preferredBrand = "Dell"
                )
            ),
            allocatedEquipmentIds = emptyList()
        )

        // No active transaction, so callback executes immediately
        publisher.publishAllocationCreated(allocation)

        val messageSlot = slot<AllocationRequestedMessage>()
        verify { rabbitTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_QUEUE, capture(messageSlot)) }

        val captured = messageSlot.captured
        assertEquals(allocationId, captured.id)
        assertEquals(1, captured.policy.size)
        assertEquals(EquipmentType.MONITOR, captured.policy[0].type)
        assertEquals(2, captured.policy[0].quantity)
        assertEquals(0.8, captured.policy[0].minimumConditionScore)
        assertEquals("Dell", captured.policy[0].preferredBrand)
    }

    @Test
    fun `publishAllocationCreated maps multiple policy requirements`() {
        val allocation = AllocationEntity(
            id = UUID.randomUUID(),
            state = AllocationState.PENDING,
            policy = listOf(
                EquipmentPolicyRequirement(type = EquipmentType.MAIN_COMPUTER, quantity = 1, preferredBrand = "Apple"),
                EquipmentPolicyRequirement(type = EquipmentType.MONITOR, quantity = 2)
            ),
            allocatedEquipmentIds = emptyList()
        )

        publisher.publishAllocationCreated(allocation)

        val messageSlot = slot<AllocationRequestedMessage>()
        verify { rabbitTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_QUEUE, capture(messageSlot)) }
        assertEquals(2, messageSlot.captured.policy.size)
    }

    @Test
    fun `publishAllocationProcessed sends success message to result queue`() {
        val allocationId = UUID.randomUUID()
        val equipmentIds = listOf(UUID.randomUUID(), UUID.randomUUID())

        publisher.publishAllocationProcessed(allocationId, true, equipmentIds)

        val messageSlot = slot<AllocationProcessedMessage>()
        verify { rabbitTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_RESULT_QUEUE, capture(messageSlot)) }

        val captured = messageSlot.captured
        assertEquals(allocationId, captured.id)
        assertTrue(captured.success)
        assertEquals(equipmentIds, captured.allocatedEquipmentIds)
    }

    @Test
    fun `publishAllocationProcessed sends failure message with empty equipment ids`() {
        val allocationId = UUID.randomUUID()

        publisher.publishAllocationProcessed(allocationId, false)

        val messageSlot = slot<AllocationProcessedMessage>()
        verify { rabbitTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_RESULT_QUEUE, capture(messageSlot)) }

        val captured = messageSlot.captured
        assertEquals(allocationId, captured.id)
        assertEquals(false, captured.success)
        assertTrue(captured.allocatedEquipmentIds.isEmpty())
    }
}

