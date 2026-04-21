package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.AllocationLockContentionException
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import com.tequipy.challenge.domain.port.spi.InventoryAllocationPort
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.system.measureNanoTime

@Service
class InventoryAllocationService(
    private val equipmentRepository: EquipmentRepository
) : InventoryAllocationPort {



    @Transactional
    override fun confirmReservedEquipment(equipmentIds: List<UUID>) {
        if (equipmentIds.isEmpty()) return
        val equipment = equipmentRepository.findByIds(equipmentIds)
        equipmentRepository.updateAll(equipment.map { it.copy(state = EquipmentState.ASSIGNED) })
    }

    @Transactional
    override fun releaseReservedEquipment(equipmentIds: List<UUID>) {
        if (equipmentIds.isEmpty()) return
        val equipment = equipmentRepository.findByIds(equipmentIds)
        equipmentRepository.updateAll(equipment.map {
            if (it.state == EquipmentState.RESERVED) it.copy(state = EquipmentState.AVAILABLE) else it
        })
    }

}
