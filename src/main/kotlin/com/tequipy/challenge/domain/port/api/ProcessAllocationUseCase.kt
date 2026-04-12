package com.tequipy.challenge.domain.port.api

interface ProcessAllocationUseCase {
    fun processAllocation(command: ProcessAllocationCommand)
}

