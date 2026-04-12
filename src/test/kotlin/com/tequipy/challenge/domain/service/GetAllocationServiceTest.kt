package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.AllocationRequest
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.port.spi.AllocationRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class GetAllocationServiceTest {
    private val allocationRepository: AllocationRepository = mockk()
    private val service = GetAllocationService(allocationRepository)

    @Test
    fun `getAllocation should return allocation when found`() {
        val allocationId = UUID.randomUUID()
        val allocation = AllocationRequest(allocationId, emptyList(), AllocationState.PENDING)
        every { allocationRepository.findById(allocationId) } returns allocation

        val result = service.getAllocation(allocationId)

        assertEquals(allocation, result)
    }

    @Test
    fun `getAllocation should throw when missing`() {
        val allocationId = UUID.randomUUID()
        every { allocationRepository.findById(allocationId) } returns null

        assertThrows(NotFoundException::class.java) {
            service.getAllocation(allocationId)
        }
    }
}

