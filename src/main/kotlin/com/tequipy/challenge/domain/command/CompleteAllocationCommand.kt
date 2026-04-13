package com.tequipy.challenge.domain.command

import java.util.UUID

data class CompleteAllocationCommand(
    val allocationId: UUID,
    val success: Boolean,
    val allocatedEquipmentIds: List<UUID>
)

