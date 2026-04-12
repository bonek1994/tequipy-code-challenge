package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.command.CreateAllocationCommand
import com.tequipy.challenge.domain.model.AllocationEntity

interface CreateAllocationUseCase {
    fun createAllocation(command: CreateAllocationCommand): AllocationEntity
}


