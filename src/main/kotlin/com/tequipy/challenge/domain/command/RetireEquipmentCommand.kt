package com.tequipy.challenge.domain.command

import java.util.UUID

data class RetireEquipmentCommand(
    val id: UUID,
    val reason: String
)

