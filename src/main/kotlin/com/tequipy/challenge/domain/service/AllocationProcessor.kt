package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.AllocationState
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

        // Issue one ranked SELECT FOR UPDATE SKIP LOCKED sub-query per requirement.
        // Each sub-query applies hard constraints (type + minimumConditionScore), ranks
        // candidates by the scoring formula (preferred brand bonus + condition score), and
        // limits results to the total quantity needed for that equipment type.  The results
        // are unioned and locked atomically, eliminating any separate non-locking pre-scan.
        val lockedCandidates = equipmentRepository.findAvailableByPolicyForUpdate(allocation.policy)

        if (lockedCandidates.isEmpty()) {
            allocationRepository.save(allocation.copy(state = AllocationState.FAILED, allocatedEquipmentIds = emptyList()))
            return
        }

        val selected = allocationAlgorithm.allocate(
            policy = allocation.policy,
            availableEquipment = lockedCandidates
        )

        if (selected == null) {
            allocationRepository.save(allocation.copy(state = AllocationState.FAILED, allocatedEquipmentIds = emptyList()))
            return
        }

        // Reserve the selected equipment; remaining locked rows are released when the
        // transaction commits.
        equipmentRepository.saveAll(selected.map { it.copy(state = EquipmentState.RESERVED) })
        allocationRepository.save(
            allocation.copy(
                state = AllocationState.ALLOCATED,
                allocatedEquipmentIds = selected.map { it.id }
            )
        )
    }
}

