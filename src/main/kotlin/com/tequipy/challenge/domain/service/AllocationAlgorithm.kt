package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType

class AllocationAlgorithm {

    companion object {
        /**
         * Multiplier applied to the number of slots in a constraint group to
         * determine how many top-scoring candidates to consider per slot.
         *
         * e.g. request for 5 monitors → 5 × 4 = top-20 candidates per slot.
         * This keeps the search space bounded while giving the algorithm enough
         * room to find a globally optimal combination.
         */
        const val CANDIDATE_MULTIPLIER = 4
    }

    fun allocate(
        policy: List<EquipmentPolicyRequirement>,
        availableEquipment: List<Equipment>
    ): List<Equipment>? {
        val eligibleEquipment = availableEquipment.filter { it.state == EquipmentState.AVAILABLE }
        val slots = policy.flatMap { requirement -> List(requirement.quantity) { requirement.copy(quantity = 1) } }
        if (slots.isEmpty()) return emptyList()

        // Count how many slots compete for the same constraint group so
        // the candidate limit always scales with the request size.
        data class SlotKey(val type: EquipmentType, val minScore: Double?)
        val slotsPerGroup = slots.groupingBy { SlotKey(it.type, it.minimumConditionScore) }.eachCount()

        // Pre-sort and limit candidates per slot to top-K by score.
        // K = groupSize × CANDIDATE_MULTIPLIER — enough headroom for the
        // backtracking search, scales naturally with request size.
        val candidatesPerSlot = slots.map { requirement ->
            val groupSize = slotsPerGroup[SlotKey(requirement.type, requirement.minimumConditionScore)] ?: 1
            val limit = groupSize * CANDIDATE_MULTIPLIER
            eligibleEquipment.filter { equipment ->
                equipment.type == requirement.type &&
                    (requirement.minimumConditionScore == null || equipment.conditionScore >= requirement.minimumConditionScore)
            }.sortedByDescending { scoreCandidate(it, requirement) }
             .take(limit)
        }

        if (candidatesPerSlot.any { it.isEmpty() }) return null

        val indexedSlots = slots.indices.sortedBy { candidatesPerSlot[it].size }

        var bestScore = Double.NEGATIVE_INFINITY
        var bestSelection: List<Equipment>? = null

        fun search(position: Int, usedIds: MutableSet<java.util.UUID>, chosen: MutableList<Equipment>, score: Double) {
            if (position == indexedSlots.size) {
                if (score > bestScore) {
                    bestScore = score
                    bestSelection = chosen.toList()
                }
                return
            }

            val slotIndex = indexedSlots[position]
            val requirement = slots[slotIndex]
            val candidates = candidatesPerSlot[slotIndex]
                .filterNot { usedIds.contains(it.id) }

            if (candidates.isEmpty()) return

            for (candidate in candidates) {
                usedIds += candidate.id
                chosen += candidate
                search(
                    position + 1,
                    usedIds,
                    chosen,
                    score + scoreCandidate(candidate, requirement)
                )
                chosen.removeAt(chosen.lastIndex)
                usedIds -= candidate.id
            }
        }

        search(0, linkedSetOf(), mutableListOf(), 0.0)
        return bestSelection
    }

    private fun scoreCandidate(
        equipment: Equipment,
        requirement: EquipmentPolicyRequirement
    ): Double {
        val brandScore = if (requirement.preferredBrand != null && equipment.brand.equals(requirement.preferredBrand, ignoreCase = true)) 10.0 else 0.0
        val conditionScore = equipment.conditionScore
        return brandScore + conditionScore
    }
}
