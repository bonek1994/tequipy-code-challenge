package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import java.util.UUID

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
        const val CANDIDATE_MULTIPLIER = 3
    }

    fun allocate(
        policy: List<EquipmentPolicyRequirement>,
        availableEquipment: List<Equipment>
    ): List<Equipment>? {
        val eligible = availableEquipment.filter { it.state == EquipmentState.AVAILABLE }
        val slots = policy.flatMap { req -> List(req.quantity) { req.copy(quantity = 1) } }
        if (slots.isEmpty()) return emptyList()

        val slotsPerGroup = slots.groupingBy { it.constraintKey() }.eachCount()

        val candidatesPerSlot = slots.map { slot ->
            val groupSize = slotsPerGroup[slot.constraintKey()] ?: 1
            eligible
                .filter { it.matchesHardConstraints(slot) }
                .sortedByDescending { it.score(slot) }
                .take(groupSize * CANDIDATE_MULTIPLIER)
        }

        if (candidatesPerSlot.any { it.isEmpty() }) return null

        val processingOrder = slots.indices.sortedBy { candidatesPerSlot[it].size }

        var bestScore = Double.NEGATIVE_INFINITY
        var bestSelection: List<Equipment>? = null

        fun search(pos: Int, usedIds: MutableSet<UUID>, chosen: MutableList<Equipment>, score: Double) {
            if (pos == processingOrder.size) {
                if (score > bestScore) {
                    bestScore = score
                    bestSelection = chosen.toList()
                }
                return
            }
            val slotIdx = processingOrder[pos]
            val candidates = candidatesPerSlot[slotIdx].filterNot { it.id in usedIds }
            for (candidate in candidates) {
                usedIds += candidate.id
                chosen += candidate
                search(pos + 1, usedIds, chosen, score + candidate.score(slots[slotIdx]))
                chosen.removeAt(chosen.lastIndex)
                usedIds -= candidate.id
            }
        }

        search(0, linkedSetOf(), mutableListOf(), 0.0)
        return bestSelection
    }

    private fun Equipment.matchesHardConstraints(req: EquipmentPolicyRequirement): Boolean =
        type == req.type && (req.minimumConditionScore == null || conditionScore >= req.minimumConditionScore)

    private fun Equipment.score(req: EquipmentPolicyRequirement): Double {
        val brandBonus = if (req.preferredBrand?.equals(brand, ignoreCase = true) == true) 10.0 else 0.0
        return brandBonus + conditionScore
    }

    private fun EquipmentPolicyRequirement.constraintKey() = ConstraintKey(type, minimumConditionScore)

    private data class ConstraintKey(val type: EquipmentType, val minScore: Double?)
}
