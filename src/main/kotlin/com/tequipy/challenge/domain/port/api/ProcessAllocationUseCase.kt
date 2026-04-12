package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.command.ProcessAllocationCommand

interface ProcessAllocationUseCase {
    fun processAllocation(command: ProcessAllocationCommand)
}

