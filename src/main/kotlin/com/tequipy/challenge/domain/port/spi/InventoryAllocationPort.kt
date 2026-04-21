package com.tequipy.challenge.domain.port.spi

import java.util.UUID

interface InventoryAllocationPort {

    fun confirmReservedEquipment(equipmentIds: List<UUID>)

    fun releaseReservedEquipment(equipmentIds: List<UUID>)
}


