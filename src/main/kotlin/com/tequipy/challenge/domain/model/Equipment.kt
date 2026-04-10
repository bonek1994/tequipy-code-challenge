package com.tequipy.challenge.domain.model

import java.util.UUID

data class Equipment(
    val id: UUID,
    val name: String,
    val serialNumber: String,
    val employeeId: UUID?
)
