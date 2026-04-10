package com.tequipy.challenge.adapter.out.persistence.entity

import jakarta.persistence.*
import java.util.UUID

@Entity
@Table(name = "equipment")
class EquipmentEntity(
    @Id
    val id: UUID,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false, unique = true)
    val serialNumber: String,

    @Column(nullable = true)
    val employeeId: UUID?
)
