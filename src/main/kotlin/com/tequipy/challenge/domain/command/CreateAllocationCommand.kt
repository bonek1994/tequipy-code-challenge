package com.tequipy.challenge.domain.command

import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement

data class CreateAllocationCommand(
    val policy: List<EquipmentPolicyRequirement>
)

