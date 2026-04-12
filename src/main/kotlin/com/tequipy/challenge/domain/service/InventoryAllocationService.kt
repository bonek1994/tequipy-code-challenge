package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.AllocationLockContentionException
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.spi.InventoryAllocationPort
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

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
        val candidateIds = findCandidateIds(policy, available)
        if (candidateIds.isEmpty()) {
            return null
        }

        val lockedCandidates = equipmentRepository.findByIdsForUpdate(candidateIds, globalMinScore)
        if (lockedCandidates.size < candidateIds.size) {
            logger.warn {
                "Lock contention for allocation $allocationId: obtained ${lockedCandidates.size}/${candidateIds.size} candidate locks, triggering retry"
            }
            throw AllocationLockContentionException(allocationId)
        }

        val selected = allocationAlgorithm.allocate(
            policy = policy,
            availableEquipment = lockedCandidates
        ) ?: return null

        equipmentRepository.saveAll(selected.map { it.copy(state = EquipmentState.RESERVED) })
        return selected.map { it.id }
    }

    @Transactional
    override fun confirmReservedEquipment(equipmentIds: List<UUID>) {
        if (equipmentIds.isEmpty()) return

        val equipments = equipmentRepository.findByIds(equipmentIds)
        equipmentRepository.saveAll(equipments.map { it.copy(state = EquipmentState.ASSIGNED) })
    }

    @Transactional
    override fun releaseReservedEquipment(equipmentIds: List<UUID>) {
        if (equipmentIds.isEmpty()) return

        val equipments = equipmentRepository.findByIds(equipmentIds)
        equipmentRepository.saveAll(
            equipments.map {
                if (it.state == EquipmentState.RESERVED) it.copy(state = EquipmentState.AVAILABLE) else it
            }
        )
    }

    private fun findCandidateIds(policy: List<EquipmentPolicyRequirement>, available: List<Equipment>): List<UUID> {
        val slots = policy.flatMap { req -> List(req.quantity) { req.copy(quantity = 1) } }
        return available.filter { equipment ->
            slots.any { req ->
                equipment.type == req.type &&
                    (req.minimumConditionScore == null || equipment.conditionScore >= req.minimumConditionScore)
            }
        }.map { it.id }
    }
}


