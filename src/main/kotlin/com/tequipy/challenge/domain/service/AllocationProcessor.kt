package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.AllocationLockContentionException
import com.tequipy.challenge.domain.model.AllocationRequest
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
    fun processAllocation(allocation: AllocationRequest) {
        // Idempotency: verify the allocation still exists and is PENDING in DB
        // (guards against duplicate message delivery)
        val dbAllocation = allocationRepository.findById(allocation.id) ?: return
        if (dbAllocation.state != AllocationState.PENDING) return

        // Compute the global minimum condition score and required equipment types from the policy.
        // Both are used as upfront DB-level filters to exclude ineligible equipment early.
        // Policy data comes from the message, keeping pre-queue and post-queue processing independent.
        // The policy is immutable after allocation creation, so the message policy matches the DB policy.
        val globalMinScore = allocation.policy.mapNotNull { it.minimumConditionScore }.minOrNull() ?: 0.0
        val requiredTypes = allocation.policy.map { it.type }.toSet()

        // Phase 1: find all AVAILABLE equipment that matches a required type and meets the
        // global minimum condition score, then determine which ones are candidates for at
        // least one required slot (hard constraints: type + per-requirement minimumConditionScore).
        val available = equipmentRepository.findAvailableWithMinConditionScore(requiredTypes, globalMinScore)
        val candidateIds = findCandidateIds(allocation.policy, available)

        if (candidateIds.isEmpty()) {
            allocationRepository.save(dbAllocation.copy(state = AllocationState.FAILED, allocatedEquipmentIds = emptyList()))
            return
        }

        // Phase 2: lock only the candidates with SELECT FOR UPDATE SKIP LOCKED,
        // also applying the global condition score filter upfront.
        // Rows already locked by concurrent transactions are skipped so we only
        // work with equipment that is truly available to this request.
        val lockedCandidates = equipmentRepository.findByIdsForUpdate(candidateIds, globalMinScore)

        // If any candidates are locked by concurrent transactions (partial or full contention),
        // throw to trigger retry so we can attempt again with all candidates available.
        if (lockedCandidates.size < candidateIds.size) {
            throw AllocationLockContentionException(allocation.id)
        }

        // Phase 3: run the scoring algorithm on the locked candidates.
        val selected = allocationAlgorithm.allocate(
            policy = allocation.policy,
            availableEquipment = lockedCandidates
        )

        if (selected == null) {
            allocationRepository.save(dbAllocation.copy(state = AllocationState.FAILED, allocatedEquipmentIds = emptyList()))
            return
        }

        // Phase 4: reserve the best-scored equipment; the remaining locked rows are
        // released automatically when this transaction commits.
        equipmentRepository.saveAll(selected.map { it.copy(state = EquipmentState.RESERVED) })
        allocationRepository.save(
            dbAllocation.copy(
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

