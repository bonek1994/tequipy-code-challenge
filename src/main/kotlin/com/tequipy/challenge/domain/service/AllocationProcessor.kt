package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.AllocationLockContentionException
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.out.AllocationRepository
import com.tequipy.challenge.domain.port.out.EquipmentRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class AllocationProcessor(
    private val allocationRepository: AllocationRepository,
    private val equipmentRepository: EquipmentRepository
) {
    private val allocationAlgorithm = AllocationAlgorithm()

    @Transactional
    fun processAllocation(allocationId: UUID) {
        val allocation = allocationRepository.findById(allocationId) ?: return
        if (allocation.state != AllocationState.PENDING) return

        // Phase 1: find all AVAILABLE equipment and determine which ones are candidates
        // for at least one required slot (hard constraints only: type + minimumConditionScore)
        val available = equipmentRepository.findByState(EquipmentState.AVAILABLE)
        val candidateIds = findCandidateIds(allocation.policy, available)

        if (candidateIds.isEmpty()) {
            allocationRepository.save(allocation.copy(state = AllocationState.FAILED, allocatedEquipmentIds = emptyList()))
            return
        }

        // Phase 2: lock only the candidates with SELECT FOR UPDATE SKIP LOCKED.
        // Rows already locked by concurrent transactions are skipped so we only
        // work with equipment that is truly available to this request.
        val lockedCandidates = equipmentRepository.findByIdsForUpdate(candidateIds)

        // If all candidates are locked by concurrent transactions, throw to trigger retry.
        if (lockedCandidates.isEmpty()) {
            throw AllocationLockContentionException(allocationId)
        }

        // Phase 3: run the scoring algorithm on the locked candidates.
        val selected = allocationAlgorithm.allocate(
            policy = allocation.policy,
            availableEquipment = lockedCandidates
        )

        if (selected == null) {
            allocationRepository.save(allocation.copy(state = AllocationState.FAILED, allocatedEquipmentIds = emptyList()))
            return
        }

        // Phase 4: reserve the best-scored equipment; the remaining locked rows are
        // released automatically when this transaction commits.
        equipmentRepository.saveAll(selected.map { it.copy(state = EquipmentState.RESERVED) })
        allocationRepository.save(
            allocation.copy(
                state = AllocationState.ALLOCATED,
                allocatedEquipmentIds = selected.map { it.id }
            )
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

