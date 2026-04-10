package com.tequipy.challenge.domain.service

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
    companion object {
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 50L
    }

    private val allocationAlgorithm = AllocationAlgorithm()

    @Transactional
    fun processAllocation(allocationId: UUID) {
        val allocation = allocationRepository.findById(allocationId) ?: return
        if (allocation.state != AllocationState.PENDING) return

        for (attempt in 0 until MAX_RETRIES) {
            // Phase 1: find all AVAILABLE equipment and determine which ones are candidates
            // for at least one required slot (hard constraints only: type + minimumConditionScore).
            // Re-read on every attempt so that equipment committed by concurrent transactions
            // between retries is taken into account (PostgreSQL READ COMMITTED semantics).
            val available = equipmentRepository.findByState(EquipmentState.AVAILABLE)
            val candidateIds = findCandidateIds(allocation.policy, available)

            if (candidateIds.isEmpty()) {
                // No equipment matches the hard constraints at all – fail permanently.
                allocationRepository.save(allocation.copy(state = AllocationState.FAILED, allocatedEquipmentIds = emptyList()))
                return
            }

            // Phase 2: lock only the candidates with SELECT FOR UPDATE SKIP LOCKED.
            // Rows already locked by concurrent transactions are skipped so we only
            // work with equipment that is truly available to this request.
            val lockedCandidates = equipmentRepository.findByIdsForUpdate(candidateIds)

            // Detect lock contention: if fewer rows were locked than candidate IDs,
            // some rows were skipped because a concurrent transaction holds their lock.
            val hasContention = lockedCandidates.size < candidateIds.size

            // Phase 3: run the scoring algorithm on the locked candidates.
            val selected = allocationAlgorithm.allocate(
                policy = allocation.policy,
                availableEquipment = lockedCandidates
            )

            if (selected != null) {
                // Phase 4: reserve the best-scored equipment; the remaining locked rows are
                // released automatically when this transaction commits.
                equipmentRepository.saveAll(selected.map { it.copy(state = EquipmentState.RESERVED) })
                allocationRepository.save(
                    allocation.copy(
                        state = AllocationState.ALLOCATED,
                        allocatedEquipmentIds = selected.map { it.id }
                    )
                )
                return
            }

            if (!hasContention) {
                // We held locks on every candidate but the algorithm still could not satisfy
                // all requirements – this is a genuine equipment shortage, not a race.
                // No benefit in retrying.
                allocationRepository.save(allocation.copy(state = AllocationState.FAILED, allocatedEquipmentIds = emptyList()))
                return
            }

            // Lock contention was detected. Sleep briefly outside active SQL statements so
            // that the competing transaction has a chance to commit and release its locks
            // before the next attempt. The sleep is intentionally inside the transaction;
            // splitting it out would require REQUIRES_NEW propagation which is incompatible
            // with the current architecture where the outer AllocationService transaction
            // must be visible to this method before it commits.
            if (attempt < MAX_RETRIES - 1) {
                Thread.sleep(RETRY_DELAY_MS)
            }
        }

        // All retry attempts exhausted – still failing due to lock contention.
        allocationRepository.save(allocation.copy(state = AllocationState.FAILED, allocatedEquipmentIds = emptyList()))
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

