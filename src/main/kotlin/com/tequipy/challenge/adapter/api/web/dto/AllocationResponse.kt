package com.tequipy.challenge.adapter.api.web.dto

import com.tequipy.challenge.domain.model.AllocationState
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

@Schema(description = "Allocation request details")
data class AllocationResponse(
    @Schema(description = "Unique identifier of the allocation request")
    val id: UUID,
    @Schema(description = "Current state of the allocation request")
    val state: AllocationState,
    @Schema(description = "Equipment policy requirements that drive this allocation")
    val policy: List<EquipmentPolicyRequirementRequest>,
    @Schema(description = "IDs of equipment items reserved or assigned by this allocation")
    val allocatedEquipmentIds: List<UUID>,
    @Schema(description = "Full details of equipment reserved or assigned by this allocation")
    val allocatedEquipments: List<EquipmentResponse>
)

