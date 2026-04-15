package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.AllocationLockContentionException
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.spi.InventoryAllocationPort
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.system.measureNanoTime

@Service
class InventoryAllocationService(
    private val equipmentRepository: EquipmentRepository
) : InventoryAllocationPort {

    private val allocationAlgorithm = AllocationAlgorithm()
    private val logger = KotlinLogging.logger {}

    @Transactional
    override fun reserveForAllocation(allocationId: UUID, policy: List<EquipmentPolicyRequirement>): List<UUID>? {
        val globalMinScore = policy.mapNotNull { it.minimumConditionScore }.minOrNull() ?: 0.0
        val requiredTypes = policy.map { it.type }.toSet()

        val available = equipmentRepository.findAvailableWithMinConditionScore(requiredTypes, globalMinScore)
        var selected: List<com.tequipy.challenge.domain.model.Equipment>?
        val algorithmDurationNanos = measureNanoTime {
            selected = allocationAlgorithm.allocate(
                policy = policy,
                availableEquipment = available
            )
        }
        AllocationAlgorithmMetrics.record(algorithmDurationNanos)
        val chosen = selected ?: return null

        val selectedIds = chosen.map { it.id }
        val lockedSelected = equipmentRepository.findByIdsForUpdate(selectedIds, globalMinScore)
        if (lockedSelected.size < selectedIds.size) {
            logger.warn {
                "Lock contention for allocation $allocationId: obtained ${lockedSelected.size}/${selectedIds.size} selected locks, triggering retry"
            }
            throw AllocationLockContentionException(allocationId)
        }

        equipmentRepository.updateAll(chosen.map { it.copy(state = EquipmentState.RESERVED) })
        return selectedIds
    }

    @Transactional
    override fun confirmReservedEquipment(equipmentIds: List<UUID>) {
        if (equipmentIds.isEmpty()) return

        val equipments = equipmentRepository.findByIds(equipmentIds)
        equipmentRepository.updateAll(equipments.map { it.copy(state = EquipmentState.ASSIGNED) })
    }

    @Transactional
    override fun releaseReservedEquipment(equipmentIds: List<UUID>) {
        if (equipmentIds.isEmpty()) return

        val equipments = equipmentRepository.findByIds(equipmentIds)
        equipmentRepository.updateAll(
            equipments.map {
                if (it.state == EquipmentState.RESERVED) it.copy(state = EquipmentState.AVAILABLE) else it
            }
        )
    }
}
