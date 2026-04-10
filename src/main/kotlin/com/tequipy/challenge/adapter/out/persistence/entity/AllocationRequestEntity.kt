package com.tequipy.challenge.adapter.out.persistence.entity

import com.tequipy.challenge.domain.model.AllocationState
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "allocation_requests")
class AllocationRequestEntity(
    @Id
    val id: UUID,

    @Column(nullable = false)
    val employeeId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val state: AllocationState,

    @ElementCollection
    @CollectionTable(
        name = "allocation_policy_requirements",
        joinColumns = [JoinColumn(name = "allocation_request_id")]
    )
    val policy: List<EquipmentPolicyRequirementEmbeddable>,

    @ElementCollection
    @CollectionTable(
        name = "allocation_equipment_ids",
        joinColumns = [JoinColumn(name = "allocation_request_id")]
    )
    @Column(name = "equipment_id", nullable = false)
    val allocatedEquipmentIds: List<UUID>
)

