package com.tequipy.challenge.domain.port.spi

import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import java.util.UUID

interface InventoryReservationPort {
    fun reserveForAllocation(allocationId: UUID, policy: List<EquipmentPolicyRequirement>): List<UUID>?

    fun confirmReservedEquipment(equipmentIds: List<UUID>)

    fun releaseReservedEquipment(equipmentIds: List<UUID>)
}

