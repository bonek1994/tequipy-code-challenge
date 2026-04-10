package com.tequipy.challenge.domain.model

import java.util.UUID

data class Employee(
    val id: UUID,
    val name: String,
    val email: String,
    val department: String
)
