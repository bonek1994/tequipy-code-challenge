package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.command.CompleteAllocationCommand

interface CompleteAllocationUseCase {
    fun completeAllocations(commands: List<CompleteAllocationCommand>)
}

