package com.tequipy.challenge.adapter.`in`.web.dto

import java.util.UUID

data class EmployeeResponse(
    val id: UUID,
    val name: String,
    val email: String,
    val department: String
)
