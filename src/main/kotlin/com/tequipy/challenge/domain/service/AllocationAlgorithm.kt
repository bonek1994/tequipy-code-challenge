package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import java.time.LocalDate

class AllocationAlgorithm {

    fun allocate(
        policy: List<EquipmentPolicyRequirement>,
        availableEquipment: List<Equipment>
    ): List<Equipment>? {
        val eligibleEquipment = availableEquipment.filter { it.state == EquipmentState.AVAILABLE }
        val slots = policy.flatMap { requirement -> List(requirement.quantity) { requirement.copy(quantity = 1) } }
        if (slots.isEmpty()) return emptyList()

        val candidatesPerSlot = slots.map { requirement ->
            eligibleEquipment.filter { equipment ->
                equipment.type == requirement.type &&
                    (requirement.minimumConditionScore == null || equipment.conditionScore >= requirement.minimumConditionScore)
            }
        }

        if (candidatesPerSlot.any { it.isEmpty() }) return null

        val indexedSlots = slots.indices.sortedBy { candidatesPerSlot[it].size }
        val recentBase = eligibleEquipment.minOfOrNull(Equipment::purchaseDate) ?: LocalDate.now()
        val recentMax = eligibleEquipment.maxOfOrNull(Equipment::purchaseDate) ?: recentBase
        val recencyRange = (recentMax.toEpochDay() - recentBase.toEpochDay()).coerceAtLeast(1)

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
                .sortedByDescending { candidate -> scoreCandidate(candidate, requirement, recentBase.toEpochDay(), recencyRange) }

            if (candidates.isEmpty()) return

            for (candidate in candidates) {
                usedIds += candidate.id
                chosen += candidate
                search(
                    position + 1,
                    usedIds,
                    chosen,
                    score + scoreCandidate(candidate, requirement, recentBase.toEpochDay(), recencyRange)
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
        requirement: EquipmentPolicyRequirement,
        minPurchaseEpochDay: Long,
        recencyRange: Long
    ): Double {
        val brandScore = if (requirement.preferredBrand != null && equipment.brand.equals(requirement.preferredBrand, ignoreCase = true)) 10.0 else 0.0
        val recencyScore = (equipment.purchaseDate.toEpochDay() - minPurchaseEpochDay).toDouble() / recencyRange
        val conditionScore = equipment.conditionScore
        return brandScore + recencyScore + conditionScore
    }
}

