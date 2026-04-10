package com.tequipy.challenge.adapter.`in`.web.dto

import java.util.UUID

data class EquipmentResponse(
    val id: UUID,
    val name: String,
    val serialNumber: String,
    val employeeId: UUID?
)
