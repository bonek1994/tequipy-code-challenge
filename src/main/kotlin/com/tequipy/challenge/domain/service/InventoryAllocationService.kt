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

    private val algorithm = AllocationAlgorithm()
    private val logger = KotlinLogging.logger {}

    @Transactional
    override fun reserveForAllocation(allocationId: UUID, policy: List<EquipmentPolicyRequirement>): List<UUID>? {
        val globalMinScore = policy.mapNotNull { it.minimumConditionScore }.minOrNull() ?: 0.0
        val requiredTypes = policy.map { it.type }.toSet()
        val available = equipmentRepository.findAvailableWithMinConditionScore(requiredTypes, globalMinScore)

        val chosen = measureAndRecord { algorithm.allocate(policy, available) } ?: return null
        val selectedIds = chosen.map { it.id }

        val locked = equipmentRepository.findByIdsForUpdate(selectedIds, globalMinScore)
        if (locked.size < selectedIds.size) {
            logger.warn { "Lock contention for allocation $allocationId: ${locked.size}/${selectedIds.size} locks obtained" }
            throw AllocationLockContentionException(allocationId)
        }

        equipmentRepository.updateAll(chosen.map { it.copy(state = EquipmentState.RESERVED) })
        return selectedIds
    }

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

    private inline fun <T> measureAndRecord(block: () -> T): T {
        var result: T
        val nanos = measureNanoTime { result = block() }
        AllocationAlgorithmMetrics.record(nanos)
        return result
    }
}
