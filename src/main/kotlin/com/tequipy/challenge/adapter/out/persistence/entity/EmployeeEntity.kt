package com.tequipy.challenge.adapter.out.persistence.entity

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "employees")
class EmployeeEntity(
    @Id
    val id: UUID,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(nullable = false)
    val department: String
)
