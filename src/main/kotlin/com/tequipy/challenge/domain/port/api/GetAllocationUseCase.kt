package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.model.AllocationEntity
import java.util.UUID

interface GetAllocationUseCase {
    fun getAllocation(id: UUID): AllocationEntity
}


